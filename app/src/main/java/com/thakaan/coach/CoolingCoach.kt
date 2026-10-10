package com.thakaan.coach

import com.thakaan.risk.Symptom

enum class CoachStep(val label: String, val seconds: Int) {
    SHADE("Move into shade and sit", 120),
    DRINK("Drink water slowly", 120),
    WET_CLOTH("Wet cloth on the neck", 180),
    FAN("Fan yourself or face the breeze", 180),
}

/** What the recovery re-check measured. A missing item is a failed item: no evidence never clears Danger. */
class RecoveryEvidence(
    val restHrBpm: Double?,
    val hrQuality: Double,
    val baselineRestHrBpm: Double?,
    /** True = flagged against the worker's own baseline, false = within baseline, null = not done. */
    val tremorFlagged: Boolean?,
    val tappingFlagged: Boolean?,
    val symptoms: Set<Symptom>,
    val coreTrendC: Double? = null,
)

class RecoveryResult(val passed: Boolean, val reasons: List<String>)

object RecoveryCheck {
    const val MAX_ABOVE_BASELINE_BPM = 10.0
    const val MAX_ABSOLUTE_REST_BPM = 95.0
    const val MIN_HR_QUALITY = 0.6
    const val MAX_CORE_C = 38.0

    fun evaluate(e: RecoveryEvidence): RecoveryResult {
        val why = ArrayList<String>()
        val hr = e.restHrBpm
        if (hr == null || e.hrQuality < MIN_HR_QUALITY) why += "no clean heart check yet"
        else {
            val limit = e.baselineRestHrBpm?.let { it + MAX_ABOVE_BASELINE_BPM } ?: MAX_ABSOLUTE_REST_BPM
            if (hr > limit) why += "resting heart rate still high"
        }
        when (e.tremorFlagged) { null -> why += "hand-hold test not done"; true -> why += "hands still shaky"; false -> {} }
        when (e.tappingFlagged) { null -> why += "tapping test not done"; true -> why += "tapping still slow"; false -> {} }
        if (e.symptoms.any { it.severe }) why += "severe symptom reported"
        else if (e.symptoms.size >= 2) why += "several symptoms still present"
        e.coreTrendC?.let { if (it >= MAX_CORE_C) why += "body trend still high" }
        return RecoveryResult(why.isEmpty(), why)
    }
}

/**
 * Ten-minute guided recovery (#43). The timer cannot be skipped: the re-check opens only after all steps have run, and
 * Danger clears only when [submit] returns a passed result, which the app hands to the risk engine as recoveryPassed.
 */
class CoolingCoach {
    private var startSec: Double? = null
    private var extraSec = 0
    var lastResult: RecoveryResult? = null; private set

    val totalSeconds: Int get() = CoachStep.entries.sumOf { it.seconds } + extraSec
    val running: Boolean get() = startSec != null

    fun start(nowSec: Double) { startSec = nowSec; extraSec = 0; lastResult = null }

    fun currentStep(nowSec: Double): CoachStep? {
        val s = startSec ?: return null
        var t = nowSec - s
        for (step in CoachStep.entries) { if (t < step.seconds) return step; t -= step.seconds }
        return null
    }

    fun secondsLeft(nowSec: Double): Int = startSec?.let { maxOf(0, (totalSeconds - (nowSec - it)).toInt()) } ?: 0

    fun recheckAllowed(nowSec: Double): Boolean = startSec != null && secondsLeft(nowSec) == 0

    /** Returns the result, or null when the re-check is not yet allowed. A failed re-check adds five minutes. */
    fun submit(nowSec: Double, evidence: RecoveryEvidence): RecoveryResult? {
        if (!recheckAllowed(nowSec)) return null
        val r = RecoveryCheck.evaluate(evidence)
        lastResult = r
        if (!r.passed) { extraSec += 300 }
        return r
    }

    val recoveryPassed: Boolean get() = lastResult?.passed == true
}
