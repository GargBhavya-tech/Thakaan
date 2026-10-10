package com.thakaan.settings

import com.thakaan.shift.CheckStepId

/**
 * What kind of work the person does. It decides the panel and which steps the check contains (a product ASSUMPTION about each
 * job, not measured with those workers yet; ticket #65 is where it gets tuned). Every kind includes the face check: where a
 * helmet, goggles, sunglasses or cap covers the face, the worker takes it off for the 40 seconds.
 *
 * - Delivery partner: the Ride panel. Checked at pickup (face, pulse, how you feel) to decide fit to ride or rest first, then
 *   watched by the front camera in short windows while riding, with the map and directions beside the score.
 * - Construction worker, police or security, other gig worker: checked on a schedule (sooner when hotter) with the fingertip
 *   pulse and the face check, and told their score and the chance of reaching Strained in the next 20 minutes.
 */
enum class WorkType(val label: String, val hint: String, val steps: List<CheckStepId>) {
    DELIVERY(
        "Delivery partner", "Phone on the bike or in hand, face free at a stop",
        listOf(CheckStepId.FACE, CheckStepId.FINGERTIP, CheckStepId.SYMPTOMS),
    ),
    CONSTRUCTION(
        "Construction worker", "Gloves, dust, helmet and noise",
        listOf(CheckStepId.FINGERTIP, CheckStepId.FACE, CheckStepId.TREMOR, CheckStepId.SYMPTOMS),
    ),
    SECURITY(
        "Police or security", "Uniform, cap, on duty around people",
        listOf(CheckStepId.FINGERTIP, CheckStepId.FACE, CheckStepId.TAPPING, CheckStepId.SYMPTOMS),
    ),
    OTHER_GIG(
        "Other gig worker", "Driver, cleaner, tradesperson and others",
        listOf(CheckStepId.FINGERTIP, CheckStepId.FACE, CheckStepId.TREMOR, CheckStepId.TAPPING, CheckStepId.SPEECH, CheckStepId.SYMPTOMS),
    );

    /** Delivery partners are checked at pickup and watched while they ride; everyone else is checked on a schedule and told what the next 20 minutes look like. */
    val rides: Boolean get() = this == DELIVERY

    companion object {
        /** An unknown or missing value is the general check, never an error. */
        fun parse(s: String?): WorkType = entries.firstOrNull { it.name == s } ?: OTHER_GIG
    }
}
