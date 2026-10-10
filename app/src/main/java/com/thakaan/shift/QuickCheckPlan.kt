package com.thakaan.shift

import com.thakaan.brain.FittsResult
import com.thakaan.brain.TremorResult
import com.thakaan.ppg.CheckResult
import com.thakaan.risk.Symptom

enum class CheckStepId(val label: String, val seconds: Int, val needsRecogniser: Boolean = false) {
    FINGERTIP("Finger on the camera", 25),
    TREMOR("Hold the phone still", 12),
    TAPPING("Tap the two circles", 20),
    SPEECH("Say the number", 10, needsRecogniser = true),
    SYMPTOMS("How do you feel?", 6),
}

/**
 * The quick check (#45): fingertip, tremor hold, tapping, speech code (only once a recogniser is wired in) and symptoms,
 * chained on one screen. A teammate who has never seen it must finish unaided in under 90 s, so the plan is budgeted.
 */
class QuickCheckPlan(speechAvailable: Boolean = false) {
    val steps: List<CheckStepId> = CheckStepId.entries.filter { !it.needsRecogniser || speechAvailable }
    val transitionSec = 3
    val totalSeconds: Int get() = steps.sumOf { it.seconds } + transitionSec * (steps.size - 1)

    fun next(after: CheckStepId?): CheckStepId? = if (after == null) steps.first() else steps.getOrNull(steps.indexOf(after) + 1)
}

/** What the flow has collected so far. A skipped step is recorded with its reason, so "not done" is never mistaken for "fine". */
class QuickCheckOutcome {
    var heart: CheckResult? = null
    var tremor: TremorResult? = null
    var fitts: FittsResult? = null
    var symptoms: Set<Symptom>? = null
    var effort: Int? = null
    val skipped = LinkedHashMap<CheckStepId, String>()

    fun isComplete(plan: QuickCheckPlan): Boolean = plan.steps.all { done(it) || it in skipped }

    fun done(s: CheckStepId): Boolean = when (s) {
        CheckStepId.FINGERTIP -> heart?.accepted == true
        CheckStepId.TREMOR -> tremor?.valid == true
        CheckStepId.TAPPING -> fitts?.valid == true
        CheckStepId.SPEECH -> false
        CheckStepId.SYMPTOMS -> symptoms != null
    }

    /** What to do next when a step fails; every failure says what to do. */
    fun advice(s: CheckStepId): String = when (s) {
        CheckStepId.FINGERTIP -> heart?.prompt ?: "Cover the camera and the light with your fingertip and keep still."
        CheckStepId.TREMOR -> tremor?.reason?.ifBlank { null } ?: "Hold the phone out at arm's length and keep still."
        CheckStepId.TAPPING -> fitts?.reason?.ifBlank { null } ?: "Tap the two circles as fast and as exactly as you can."
        CheckStepId.SPEECH -> "Say the four numbers clearly."
        CheckStepId.SYMPTOMS -> "Tap anything you feel, or tap None."
    }

    fun summary(): List<String> = buildList {
        heart?.let { add(if (it.accepted) "Heart check done" else "Heart check not usable: ${it.prompt ?: "try again"}") }
        tremor?.let { add(if (!it.valid) "Hand hold not usable" else if (it.liftingConfound) "Hand hold taken just after lifting: noted, not scored" else "Hand hold done") }
        fitts?.let { add(if (it.valid) "Tapping done" else "Tapping not usable") }
        symptoms?.let { add(if (it.isEmpty()) "No symptoms reported" else "Symptoms reported") }
        skipped.forEach { (s, why) -> add("${s.label}: skipped ($why)") }
    }
}
