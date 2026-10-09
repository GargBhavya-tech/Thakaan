package com.thakaan.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * Ticket #41: the safety invariant as an executable property. If this fails, fix the engine, never the test.
 *
 * Meaning of "degraded runs never show a lower level": information loss cannot be allowed to reassure. We compare a
 * degraded run with a REFERENCE run that has the same history and the same true exposure, but whose body, brain and
 * symptom evidence stops updating at the moment of degradation (nobody can know what a lost sensor would have said).
 * The degraded run may lose or corrupt that evidence further and may lose exposure inputs (GNSS, light, weather), and it
 * must still never show a lower level than the reference.
 */
class SafetyPropertyTest {
    private val MIN = 60_000_000_000L

    private enum class Op { DROP_HR, LOW_QUALITY_HR, GNSS_LOST, LIGHT_BLOCKED, WBGT_MISSING, MODEL_OFF }

    private class Raw(
        val wbgt: Double, val dose: Double, val debt: Double, val hour: Double, val accl: Int, val sun: Boolean,
        val readingMinute: Int, val psi: Double, val core: Double, val restHr: Double, val restMin: Double,
        val families: Set<BrainFamily>, val symptoms: Set<Symptom>,
    )

    private fun genShift(rng: Random): List<Raw> {
        val n = 240
        var wbgt = 26.0 + rng.nextDouble() * 9.0
        var dose = rng.nextDouble() * 60.0
        var debt = rng.nextDouble() * 40.0
        val hour0 = 10.0 + rng.nextDouble() * 4.0
        val accl = rng.nextInt(14)
        var psi = rng.nextDouble() * 5.0
        var core = 36.9 + rng.nextDouble() * 1.2
        var restHr = 70.0 + rng.nextDouble() * 35.0
        var families = emptySet<BrainFamily>()
        var symptoms = emptySet<Symptom>()
        var readingMinute = 0
        val out = ArrayList<Raw>()
        for (m in 0 until n) {
            wbgt = (wbgt + rng.nextGaussian() * 0.25).coerceIn(20.0, 36.0)
            val sun = rng.nextInt(10) < 6
            if (sun) dose += 1.0
            debt += if (rng.nextInt(30) == 0) -debt else 1.0
            if (m % 5 == 0) { // a fresh body reading every 5 minutes
                readingMinute = m
                psi = (psi + rng.nextGaussian() * 0.8).coerceIn(0.0, 9.5)
                core = (core + rng.nextGaussian() * 0.15).coerceIn(36.5, 40.0)
                restHr = (restHr + rng.nextGaussian() * 4.0).coerceIn(55.0, 130.0)
            }
            if (rng.nextInt(40) == 0) families = BrainFamily.entries.filter { rng.nextInt(4) == 0 }.toSet()
            if (rng.nextInt(60) == 0) symptoms = Symptom.entries.filter { rng.nextInt(6) == 0 }.toSet()
            out += Raw(wbgt, dose, maxOf(0.0, debt), hour0 + m / 60.0, accl, sun, readingMinute, psi, core, restHr, if (m % 7 < 5) 6.0 else 2.0, families, symptoms)
        }
        return out
    }

    private fun inputs(m: Int, r: Raw, ops: Set<Op>, frozenAt: Raw?, shadeMinutesSinceDegrade: Int, reference: Boolean): RiskInputs {
        // evidence source: the live raw for the true run, the frozen raw for reference and degraded runs after t0
        val ev = frozenAt ?: r
        val age = (m - ev.readingMinute) * 60.0 + 30.0
        var body = BodyInputs(
            hrAgeSec = age, hrQuality = 0.9, restHrBpm = ev.restHr, restHrSustainedMin = ev.restMin,
            coreTrendC = ev.core, psi = ev.psi,
        )
        var families = ev.families
        var sunNow: Boolean? = r.sun
        var wbgt: Double? = r.wbgt
        var dose = r.dose
        var modelAvailable = true
        if (!reference) {
            if (Op.DROP_HR in ops) body = BodyInputs() // nothing at all: no reading, no age
            if (Op.LOW_QUALITY_HR in ops) body = BodyInputs(hrAgeSec = age, hrQuality = 0.1, restHrBpm = ev.restHr, restHrSustainedMin = ev.restMin, coreTrendC = ev.core, psi = ev.psi)
            if (Op.GNSS_LOST in ops || Op.LIGHT_BLOCKED in ops) { sunNow = null; dose += shadeMinutesSinceDegrade }
            if (Op.WBGT_MISSING in ops) wbgt = null
            if (Op.MODEL_OFF in ops) modelAvailable = false
        }
        return RiskInputs(
            m * MIN, ExposureInputs(wbgt, dose, r.debt, r.hour, r.accl, sunNow), body,
            BrainInputs(families, modelAvailable), ev.symptoms,
        )
    }

    /** Returns how many (time, run) pairs showed a degraded level lower than the reference. */
    private fun violations(seed: Long, cases: Int, degradedGuards: Guards = Guards(), forceOps: Set<Op>? = null, verbose: Boolean = false): Int {
        val rng = Random(seed)
        var bad = 0
        var printed = false
        repeat(cases) {
            val shift = genShift(rng)
            val t0 = 20 + rng.nextInt(120)
            val randomOps = Op.entries.filter { rng.nextInt(3) == 0 }.toSet().ifEmpty { setOf(Op.entries[rng.nextInt(Op.entries.size)]) }
            val ops = forceOps ?: randomOps
            val frozen = shift[t0 - 1] // the last evidence the engine actually saw before the channel went away

            val ref = RiskEngine()
            val deg = RiskEngine(degradedGuards)
            var shadeMinutes = 0
            for (m in shift.indices) {
                val live = m < t0
                val r = shift[m]
                if (!live && !r.sun) shadeMinutes++
                val refOut = ref.update(inputs(m, r, emptySet(), if (live) null else frozen, 0, true))
                val degOut = deg.update(inputs(m, r, ops, if (live) null else frozen, shadeMinutes, live))
                val refLevel = refOut.level
                val degLevel = degOut.level
                if (!live && degLevel < refLevel) {
                    bad++
                    if (verbose && !printed) {
                        printed = true
                        println("DIAG ops=$ops t0=$t0 m=$m ref=${refLevel} tss=${refOut.tss} anchors=${refOut.anchors} | deg=${degLevel} tss=${degOut.tss} anchors=${degOut.anchors} | wbgt=${r.wbgt} sun=${r.sun}")
                    }
                }
            }
        }
        return bad
    }

    @Test fun degradedInformationNeverLowersTheLevel() {
        val bad = violations(seed = 20261010L, cases = 1500)
        assertEquals("degraded runs showed a lower level than the reference $bad times", 0, bad)
    }

    @Test fun holdingRuleIsWhatKeepsItSafe() {
        assertTrue("without the hold the property must fail", violations(7L, 1500, Guards(holdWhenEvidenceStale = false)) > 0)
    }

    @Test fun exposureFloorIsWhatKeepsItSafe() {
        assertTrue("without the exposure floor the property must fail", violations(8L, 1500, Guards(exposureFloorOnStale = false)) > 0)
    }

    @Test fun unknownShadeAsSunIsWhatKeepsItSafe() {
        assertTrue("without unknown-shade = sun the property must fail", violations(9L, 1500, Guards(unknownShadeIsSun = false)) > 0)
    }

    @Test fun missingWeatherAsHotIsWhatKeepsItSafe() {
        assertTrue("without missing-WBGT = hot the property must fail", violations(10L, 1500, Guards(missingWbgtIsHot = false)) > 0)
    }
}
