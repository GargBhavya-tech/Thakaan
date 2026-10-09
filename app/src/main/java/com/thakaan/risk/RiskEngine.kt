package com.thakaan.risk

import com.thakaan.core.Provenance
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

enum class BrainFamily { MOTION, VOICE, TOUCH, FACE, NAVIGATION }

enum class Symptom {
    THIRST, HEADACHE, DIZZINESS, NAUSEA, CRAMPS, CONFUSION, FAINTNESS;

    val severe: Boolean get() = this == CONFUSION || this == FAINTNESS
}

/**
 * Switches for each fail-safe rule. All ON in the product. The Safety checks tab flips one OFF at a time to prove its
 * scenario then goes red ("a check that cannot fail proves nothing"), and the property test uses the same switches.
 */
data class Guards(
    /** Stale or missing body evidence is held, never decayed toward "fine". */
    val holdWhenEvidenceStale: Boolean = true,
    /** Stale or missing heart rate in severe exposure cannot reassure: exposure sets a floor. */
    val exposureFloorOnStale: Boolean = true,
    /** Unknown shade (GNSS lost, light blocked) counts as sun. */
    val unknownShadeIsSun: Boolean = true,
    /** Unknown WBGT (weather cache missing) counts as hot. */
    val missingWbgtIsHot: Boolean = true,
    /** Two flagged brain families force at least Strained. */
    val brainFamiliesFloorStrained: Boolean = true,
    /** The LLM only phrases a level; it can never change one. */
    val llmCannotChangeLevel: Boolean = true,
    /** Core-temperature anchors fire at 38.5 C (38.0 C before acclimatisation). */
    val coreAnchorsFire: Boolean = true,
    /** The independent resting-HR rule, which does not depend on the core estimate. */
    val independentHrRule: Boolean = true,
)

class ExposureInputs(
    val wbgtC: Double?,
    val sunDoseMinutes: Double,
    val restDebtMinutes: Double,
    val localHour: Double,
    val acclimatisationDays: Int,
    /** True = sun, false = shade, null = unknown. */
    val sunNow: Boolean?,
)

class BodyInputs(
    /** Seconds since the last accepted heart reading; null = never. */
    val hrAgeSec: Double? = null,
    /** Quality weight (0 to 1) of that reading. */
    val hrQuality: Double = 0.0,
    val restHrBpm: Double? = null,
    val restHrSustainedMin: Double = 0.0,
    val coreTrendC: Double? = null,
    val psi: Double? = null,
    val excessHrBpm: Double? = null,
    val excessHrLimitBpm: Double = 20.0,
    val hydrationDeficitPct: Double? = null,
    val perfusionCollapse: Boolean = false,
)

class BrainInputs(val flaggedFamilies: Set<BrainFamily> = emptySet(), val modelAvailable: Boolean = true)

class RiskInputs(
    val tNanos: Long,
    val exposure: ExposureInputs,
    val body: BodyInputs = BodyInputs(),
    val brain: BrainInputs = BrainInputs(),
    val symptoms: Set<Symptom> = emptySet(),
    val collapse: Boolean = false,
    /** A full check plus brain tests passed after Danger (ticket #43). The only thing that clears Danger. */
    val recoveryPassed: Boolean = false,
    val provenance: Provenance = Provenance.REAL,
)

class Driver(val code: String, val text: String, val weight: Double)

class RiskOutput(
    val level: Level,
    val tss: Int,
    val tssLow: Int,
    val tssHigh: Int,
    val drivers: List<Driver>,
    val anchors: List<String>,
    val changedFrom: Level?,
    val collapse: Boolean,
    val provenance: Provenance,
)

data class Weights(val wE: Double = 3.0, val wB: Double = 3.0, val wN: Double = 3.0, val wS: Double = 3.0, val bias: Double = -3.0)

/**
 * Thakaan Strain Score and anchored levels.
 *   TSS = 100 sigma(wE E + wB B + wN N + wS S + b)
 * and the shown level is the HIGHEST that the score or any anchor supports. Never a body-temperature number.
 *
 * Fail-safe rules (invariant 3) are the [Guards]. The level rises immediately but falls only after a dwell time and a
 * margin below the boundary, and Danger clears only after a passed recovery check.
 */
class RiskEngine(val guards: Guards = Guards(), private val w: Weights = Weights()) {
    var level: Level = Level.COOL
        private set

    private var lastFreshBody = 0.0
    /** Highest level any BODY anchor supported on the last fresh reading; held (no expiry) until a fresh reading replaces it. */
    private var heldBodyAnchor = Level.COOL
    private var belowSinceNanos: Long? = null
    private val latched = HashMap<String, Pair<Level, Long>>() // anchor code -> (level, expiresAtNanos)
    private var dangerLatched = false

    private val dwellToDropNanos = mapOf(Level.STRAINED to 3L * 60_000_000_000L, Level.WARMING to 5L * 60_000_000_000L)
    private val anchorLatchNanos = 10L * 60_000_000_000L
    private val staleAfterSec = 15.0 * 60.0
    private val dropMargin = 3

    /** The LLM may suggest a level; it never gets one. Returns the level actually in force. */
    fun acceptLlmSuggestion(suggested: Level): Level {
        if (!guards.llmCannotChangeLevel) level = suggested // deliberate bug path, only reachable when the guard is off
        return level
    }

    // ---------------------------------------------------------------- scoring pieces

    private fun clamp01(x: Double) = max(0.0, min(1.0, x))

    private fun lerp(x: Double, x0: Double, x1: Double, y0: Double, y1: Double) = y0 + (y1 - y0) * clamp01((x - x0) / (x1 - x0))

    private fun wbgtScore(wbgt: Double?): Double = when {
        // Unknown weather scores as the most extreme heat, so no real reading can ever be more pessimistic than missing data.
        wbgt == null -> if (guards.missingWbgtIsHot) 1.0 else 0.0
        wbgt <= 24.0 -> 0.0
        wbgt <= 28.0 -> lerp(wbgt, 24.0, 28.0, 0.0, 0.35)
        wbgt <= 31.0 -> lerp(wbgt, 28.0, 31.0, 0.35, 0.7)
        else -> lerp(wbgt, 31.0, 33.0, 0.7, 1.0)
    }

    private fun timeFactor(h: Double): Double = when {
        h in 12.0..16.0 -> 1.0
        h in 10.0..12.0 || h in 16.0..17.5 -> 0.85
        h in 8.0..10.0 || h in 17.5..19.0 -> 0.65
        else -> 0.45
    }

    private fun exposurePrior(e: ExposureInputs): Double {
        val sunNow = when (e.sunNow) { true -> 1.0; false -> 0.0; null -> if (guards.unknownShadeIsSun) 1.0 else 0.0 }
        val acclim = 1.0 - 0.2 * clamp01(e.acclimatisationDays / 10.0)
        val raw = 0.5 * wbgtScore(e.wbgtC) + 0.2 * clamp01(e.sunDoseMinutes / 120.0) + 0.15 * clamp01(e.restDebtMinutes / 60.0) + 0.15 * sunNow
        return clamp01(raw * timeFactor(e.localHour) * acclim)
    }

    private fun bodyReadingStrain(b: BodyInputs, acclimatised: Boolean): Double {
        var s = 0.0
        b.psi?.let { s = max(s, it / 10.0) }
        b.coreTrendC?.let { s = max(s, lerp(it, 37.0, if (acclimatised) 38.5 else 38.0, 0.0, 1.0)) }
        b.excessHrBpm?.let { s = max(s, 0.6 * clamp01(it / (2 * b.excessHrLimitBpm))) }
        b.hydrationDeficitPct?.let { s = max(s, 0.5 * clamp01(it / 4.0)) }
        if (b.perfusionCollapse) s = max(s, 0.5)
        if (b.restHrBpm != null && b.restHrSustainedMin >= 5.0) s = max(s, lerp(b.restHrBpm, 90.0, 110.0, 0.3, 0.9))
        return clamp01(s)
    }

    // ---------------------------------------------------------------- the minute step

    fun update(i: RiskInputs): RiskOutput {
        // A passed recovery check is the only thing that clears Danger: step down once, then ordinary dwell rules apply.
        if (level == Level.DANGER && i.recoveryPassed && !stillDangerous(i)) {
            latched.entries.removeAll { it.value.first == Level.DANGER }
            dangerLatched = false
            level = Level.STRAINED
            belowSinceNanos = null
            heldBodyAnchor = minOf(heldBodyAnchor, Level.STRAINED)
        }
        val acclimatised = i.exposure.acclimatisationDays >= 7
        val e = exposurePrior(i.exposure)
        val drivers = ArrayList<Driver>()

        // ---- body term with freshness
        val b = i.body
        val fresh = b.hrAgeSec != null && b.hrAgeSec <= staleAfterSec && b.hrQuality >= 0.5
        val reading = bodyReadingStrain(b, acclimatised)
        val bodyStrain: Double
        var q = 0.0
        if (fresh) {
            lastFreshBody = reading
            q = b.hrQuality * (1.0 - b.hrAgeSec!! / staleAfterSec).coerceIn(0.0, 1.0)
            bodyStrain = reading
        } else {
            // Not fresh: HOLD the last fresh strain and never let it decay; exposure sets a floor.
            val held = if (guards.holdWhenEvidenceStale) lastFreshBody else reading * b.hrQuality
            val floor = if (guards.exposureFloorOnStale && e >= 0.8) 0.5 * e else 0.0
            bodyStrain = max(held, floor)
            if (b.hrAgeSec == null || b.hrAgeSec > staleAfterSec) drivers += Driver("no_fresh_hr", "no fresh heart reading", 0.15 + 0.2 * e)
        }

        // ---- brain and symptoms
        val families = i.brain.flaggedFamilies
        val n = when (families.size) { 0 -> 0.0; 1 -> 0.4; 2 -> 0.8; else -> 1.0 }
        val sev = i.symptoms.any { it.severe }
        val sym = if (sev) 1.0 else clamp01(0.4 * i.symptoms.size)

        val z = w.wE * e + w.wB * bodyStrain + w.wN * n + w.wS * sym + w.bias
        val tss = (100.0 / (1.0 + exp(-z))).toInt().coerceIn(0, 100)
        val uncertainty = if (fresh) (1.0 - q) * 0.25 else 0.35
        val tssLow = (tss - 100 * uncertainty * 0.5).toInt().coerceIn(0, 100)
        val tssHigh = (tss + 100 * uncertainty * 0.5).toInt().coerceIn(0, 100)
        val scoreLevel = levelForTss(tss)

        // ---- anchors (floors)
        val anchors = ArrayList<String>()
        fun anchor(code: String, lvl: Level, text: String, weight: Double) {
            anchors += code
            latched[code] = lvl to (i.tNanos + anchorLatchNanos)
            drivers += Driver(code, text, weight)
        }
        val actionLimit = if (acclimatised) 28.0 else 25.0
        val wbgt = i.exposure.wbgtC
        if (wbgt != null && wbgt >= actionLimit) anchor("wbgt_action", Level.WARMING, "WBGT %.0f above the work-rest limit".format(wbgt), 0.4)
        if (wbgt == null && guards.missingWbgtIsHot) anchor("wbgt_unknown", Level.WARMING, "weather unknown: assumed hot", 0.3)
        if (i.exposure.sunNow == null && guards.unknownShadeIsSun) drivers += Driver("shade_unknown", "shade unknown: counted as sun", 0.2)
        // Body anchors come only from a FRESH reading. When the reading goes stale or is lost, the last fresh anchor level is
        // held (no expiry) until a fresh reading replaces it, so losing the channel can never lower the level.
        var bodyAnchorNow = Level.COOL
        fun bodyAnchor(code: String, lvl: Level, text: String, weight: Double) {
            anchor(code, lvl, text, weight)
            bodyAnchorNow = maxOf(bodyAnchorNow, lvl)
        }
        if (fresh) {
            b.coreTrendC?.let { c ->
                if (guards.coreAnchorsFire) {
                    val strainedAt = if (acclimatised) 38.5 else 38.0
                    if (c >= 39.0) bodyAnchor("core_high", Level.DANGER, "core trend very high", 1.0)
                    else if (c >= strainedAt) bodyAnchor("core_anchor", Level.STRAINED, "core trend at the action point", 0.8)
                }
            }
            b.psi?.let { p ->
                if (p > 7) bodyAnchor("psi_danger", Level.DANGER, "physiological strain very high", 0.95)
                else if (p > 5) bodyAnchor("psi_strained", Level.STRAINED, "physiological strain high", 0.7)
                else if (p > 3) bodyAnchor("psi_warming", Level.WARMING, "physiological strain rising", 0.4)
            }
            b.excessHrBpm?.let { if (it > b.excessHrLimitBpm) bodyAnchor("excess_hr", Level.STRAINED, "heart rate high for the work", 0.6) }
            b.hydrationDeficitPct?.let { if (it > 2.0) bodyAnchor("hydration", Level.STRAINED, "%.1f%% water deficit".format(it), 0.55) }
            if (guards.independentHrRule && b.restHrBpm != null && b.restHrSustainedMin >= 5.0) {
                if (b.restHrBpm >= 100.0) bodyAnchor("rest_hr_high", Level.STRAINED, "resting heart rate above 100", 0.65)
                else if (b.restHrBpm >= 90.0) bodyAnchor("rest_hr", Level.WARMING, "resting heart rate above 90", 0.4)
            }
            heldBodyAnchor = bodyAnchorNow
        } else if (guards.holdWhenEvidenceStale && heldBodyAnchor > Level.COOL) {
            bodyAnchorNow = heldBodyAnchor
            drivers += Driver("held_body", "last body reading held (no fresh one)", 0.3)
        }
        if (families.size >= 2 && guards.brainFamiliesFloorStrained) {
            val danger = families.size >= 3 || e >= 0.5 || i.symptoms.isNotEmpty()
            anchor("brain_families", if (danger) Level.DANGER else Level.STRAINED, "${families.size} brain signals slower: ${families.joinToString { it.name.lowercase() }}", 0.9)
        } else if (families.size == 1) drivers += Driver("brain_one", "one brain signal slower: ${families.first().name.lowercase()}", 0.3)
        if (sev) anchor("symptom_severe", Level.DANGER, "severe symptom reported", 1.0)
        else if (i.symptoms.isNotEmpty()) {
            val serious = i.symptoms.any { it == Symptom.DIZZINESS || it == Symptom.NAUSEA }
            if (serious && e >= 0.5) anchor("symptom_exposure", Level.STRAINED, "dizzy or sick in heavy exposure", 0.7)
            else if (i.symptoms.size >= 2) anchor("symptoms", Level.WARMING, "several symptoms", 0.35)
            else drivers += Driver("symptom", "reported ${i.symptoms.first().name.lowercase()}", 0.25)
        }
        if (i.collapse) anchor("collapse", Level.DANGER, "collapse detected", 1.0)

        // Exposure contributions to the drivers list
        if (i.exposure.sunDoseMinutes >= 30) drivers += Driver("sun_dose", "%d min in direct sun".format(i.exposure.sunDoseMinutes.toInt()), 0.2 * clamp01(i.exposure.sunDoseMinutes / 120.0) + 0.1)
        if (i.exposure.restDebtMinutes >= 10) drivers += Driver("rest_debt", "no real rest for a while", 0.15 * clamp01(i.exposure.restDebtMinutes / 60.0) + 0.1)
        if (fresh && bodyStrain >= 0.3) drivers += Driver("body", "body strain building", 0.3 * bodyStrain + 0.1)

        // active latches, including ones set on earlier minutes
        latched.entries.removeAll { it.value.second < i.tNanos && it.value.first != Level.DANGER }
        var anchorLevel = maxOf(latched.values.maxOfOrNull { it.first } ?: Level.COOL, bodyAnchorNow)
        if (anchorLevel == Level.DANGER) dangerLatched = true

        // ---- level with hysteresis
        val target = maxOf(scoreLevel, anchorLevel)
        val before = level
        if (target > level) {
            level = target
            belowSinceNanos = null
        } else if (target < level) {
            if (level == Level.DANGER) {
                // Danger holds: only the recovery step at the top of update() can lower it.
            } else {
                // drop one level only if comfortably below the boundary for the dwell time
                val boundary = if (level == Level.STRAINED) 55 else 30
                val clearlyBelow = tss < boundary - dropMargin && anchorLevel < level
                if (clearlyBelow) {
                    val since = belowSinceNanos ?: i.tNanos.also { belowSinceNanos = it }
                    if (i.tNanos - since >= (dwellToDropNanos[level] ?: 0L)) { level = Level.entries[level.ordinal - 1]; belowSinceNanos = null }
                } else belowSinceNanos = null
            }
        } else belowSinceNanos = null

        val top = drivers.distinctBy { it.code }.sortedByDescending { it.weight }.take(3)
        return RiskOutput(level, tss, tssLow, tssHigh, top, anchors, if (level != before) before else null, i.collapse || (dangerLatched && "collapse" in latched), i.provenance)
    }

    private fun stillDangerous(i: RiskInputs): Boolean = i.collapse || i.symptoms.any { it.severe }

    companion object {
        fun levelForTss(tss: Int): Level = when {
            tss < 30 -> Level.COOL
            tss < 55 -> Level.WARMING
            tss < 80 -> Level.STRAINED
            else -> Level.DANGER
        }
    }
}
