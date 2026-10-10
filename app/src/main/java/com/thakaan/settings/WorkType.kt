package com.thakaan.settings

import com.thakaan.shift.CheckStepId

/**
 * What kind of work the person does. It decides which steps the Quick check contains, because the jobs differ in what a phone can
 * honestly read from them. The mapping below is a product ASSUMPTION about each job (not measured with those workers yet), kept in
 * one table so it is easy to change after talking to real workers (ticket #65):
 *
 * - Delivery partner: the phone is in hand or on the bike all day and the face is free at a stop, so the face check (tiredness
 *   signs and a pulse estimate) is part of the check, and tapping is natural for them.
 * - Construction worker: gloves, dust, a helmet and goggles, and a noisy site. A face behind goggles and a helmet cannot be read, a
 *   gloved finger cannot tap two small circles, and spoken numbers drown in noise, so the check is the fingertip pulse (wipe the
 *   finger first), the hand hold, and how they feel. It is also the shortest, for a break.
 * - Police or security: cap or sunglasses, on duty near people. No face check, no spoken numbers in public; pulse, hand hold,
 *   tapping and symptoms.
 * - Other gig worker (driver, cleaner, tradesperson and others): the general check that was built first.
 */
enum class WorkType(val label: String, val hint: String, val steps: List<CheckStepId>) {
    DELIVERY(
        "Delivery partner", "Phone on the bike or in hand, face free at a stop",
        listOf(CheckStepId.FINGERTIP, CheckStepId.FACE, CheckStepId.TAPPING, CheckStepId.SPEECH, CheckStepId.SYMPTOMS),
    ),
    CONSTRUCTION(
        "Construction worker", "Gloves, dust, helmet and noise",
        listOf(CheckStepId.FINGERTIP, CheckStepId.TREMOR, CheckStepId.SYMPTOMS),
    ),
    SECURITY(
        "Police or security", "Uniform, cap, on duty around people",
        listOf(CheckStepId.FINGERTIP, CheckStepId.TREMOR, CheckStepId.TAPPING, CheckStepId.SYMPTOMS),
    ),
    OTHER_GIG(
        "Other gig worker", "Driver, cleaner, tradesperson and others",
        listOf(CheckStepId.FINGERTIP, CheckStepId.TREMOR, CheckStepId.TAPPING, CheckStepId.SPEECH, CheckStepId.SYMPTOMS),
    );

    /** The face check is offered on its own (Check tab) only where a face can be read. */
    val facePossible: Boolean get() = this == DELIVERY || this == OTHER_GIG

    companion object {
        /** An unknown or missing value is the general check, never an error. */
        fun parse(s: String?): WorkType = entries.firstOrNull { it.name == s } ?: OTHER_GIG
    }
}
