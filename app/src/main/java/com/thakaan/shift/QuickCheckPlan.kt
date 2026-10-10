package com.thakaan.shift

import com.thakaan.brain.FittsResult
import com.thakaan.brain.SpeechCodeResult
import com.thakaan.brain.TremorResult
import com.thakaan.ppg.CheckResult
import com.thakaan.risk.Symptom
import com.thakaan.settings.WorkType

enum class CheckStepId(val label: String, val seconds: Int, val needsRecogniser: Boolean = false) {
    FINGERTIP("Finger on the camera", 35),
    TREMOR("Hold the phone still", 12),
    TAPPING("Tap the two circles", 20),
    SPEECH("Say the number", 10, needsRecogniser = true),
    FACE("Look at the screen", 40),
    SYMPTOMS("How do you feel?", 6),
}

/**
 * The quick check (#45): fingertip, tremor hold, tapping, speech code (only once a recogniser is wired in) and symptoms,
 * chained on one screen. A teammate who has never seen it must finish unaided in under 90 s, so the plan is budgeted.
 */
class QuickCheckPlan(speechAvailable: Boolean = false, val workType: WorkType = WorkType.OTHER_GIG) {
    /** The steps for this kind of work, in order (see [WorkType]); the spoken-number step only when a recogniser is installed. */
    val steps: List<CheckStepId> = workType.steps.filter { !it.needsRecogniser || speechAvailable }
    val transitionSec = 3
    val totalSeconds: Int get() = steps.sumOf { it.seconds } + transitionSec * (steps.size - 1)

    fun next(after: CheckStepId?): CheckStepId? = if (after == null) steps.first() else steps.getOrNull(steps.indexOf(after) + 1)

    companion object {
        /** Rounded to 5 s for the buttons that say how long the check takes (the spoken step is left out: it needs a recogniser). */
        fun approxSeconds(w: WorkType): Int = ((QuickCheckPlan(false, w).totalSeconds + 2) / 5) * 5
    }
}

/** What the flow has collected so far. A skipped step is recorded with its reason, so "not done" is never mistaken for "fine". */
class QuickCheckOutcome {
    var heart: CheckResult? = null
    var tremor: TremorResult? = null
    var fitts: FittsResult? = null
    var speech: SpeechCodeResult? = null
    var symptoms: Set<Symptom>? = null
    /** The face step: [faceDone] only when the camera read something usable; [faceSummary] is the line for the summary. */
    var faceDone = false
    var faceSummary: String? = null
    var effort: Int? = null
    val skipped = LinkedHashMap<CheckStepId, String>()

    fun isComplete(plan: QuickCheckPlan): Boolean = plan.steps.all { done(it) || it in skipped }

    fun done(s: CheckStepId): Boolean = when (s) {
        CheckStepId.FINGERTIP -> heart?.accepted == true
        CheckStepId.TREMOR -> tremor?.valid == true
        CheckStepId.TAPPING -> fitts?.valid == true
        CheckStepId.SPEECH -> speech?.valid == true
        CheckStepId.SYMPTOMS -> symptoms != null
        CheckStepId.FACE -> faceDone
    }

    /** What to do next when a step fails; every failure says what to do. */
    fun advice(s: CheckStepId): String = when (s) {
        CheckStepId.FINGERTIP -> heart?.prompt ?: "Cover the camera and the light with your fingertip and keep still."
        CheckStepId.TREMOR -> tremor?.reason?.ifBlank { null } ?: "Hold the phone out at arm's length and keep still."
        CheckStepId.TAPPING -> fitts?.reason?.ifBlank { null } ?: "Tap the two circles as fast and as exactly as you can."
        CheckStepId.SPEECH -> speech?.reason?.ifBlank { null } ?: "Say the four numbers clearly, one after the other."
        CheckStepId.SYMPTOMS -> "Tap anything you feel, or tap None."
        CheckStepId.FACE -> "Hold the phone at arm's length, look at the screen in good light but not direct sun, and keep still."
    }

    fun summary(): List<String> = buildList {
        heart?.let {
            add(
                if (it.accepted) "Heart rate from your fingertip: about %.0f beats a minute".format(it.hrBpm ?: 0.0)
                else "Heart check not trusted: ${it.prompt ?: "try again"}" +
                    (it.bestEstimateBpm?.let { b -> " (best guess was about %.0f, not used)".format(b) } ?: ""),
            )
        }
        tremor?.let { add(if (!it.valid) "Hand hold not usable" else if (it.liftingConfound) "Hand hold taken just after lifting: noted, not scored" else "Hand hold done") }
        fitts?.let { add(if (it.valid) "Tapping done" else "Tapping not usable") }
        speech?.let { add(if (!it.valid) "Number test not usable: ${it.reason}" else if (it.correct) "Number test done" else "Number test done, some digits did not match") }
        symptoms?.let { add(if (it.isEmpty()) "No symptoms reported" else "Symptoms reported") }
        faceSummary?.let { add(it) }
        skipped.forEach { (s, why) -> add("${s.label}: skipped ($why)") }
    }
}
