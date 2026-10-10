package com.thakaan.ppg

import kotlin.math.abs

class GuardDecision(val allowed: Boolean, val prompt: String?)

/**
 * Thermal guard for fingertip captures (#12). In 40 C sun the OS can dim the torch or drop frames mid-capture, which
 * silently corrupts beat intervals, so a capture is blocked when the phone is hot and a window is voided if the torch
 * dims (a DC step in the red channel).
 *
 * Status values are android.os.PowerManager.THERMAL_STATUS_*: NONE 0, LIGHT 1, MODERATE 2, SEVERE 3, CRITICAL 4,
 * EMERGENCY 5, SHUTDOWN 6. Headroom is PowerManager.getThermalHeadroom(): 1.0 means throttling starts.
 */
object ThermalGuard {
    const val SEVERE = 3
    const val HEADROOM_BLOCK = 0.95f

    fun captureAllowed(thermalStatus: Int, headroom: Float?): GuardDecision {
        if (thermalStatus >= SEVERE) return GuardDecision(false, "Phone is too hot. Cool the phone in shade, then try again.")
        if (headroom != null && !headroom.isNaN() && headroom >= HEADROOM_BLOCK) {
            return GuardDecision(false, "Phone is nearly throttling. Cool the phone in shade, then try again.")
        }
        return GuardDecision(true, if (thermalStatus >= 2) "Phone is warm: keep this check short." else null)
    }

    /**
     * True when the red-channel mean changes by more than [stepFraction] between consecutive 1-second blocks and stays
     * changed, which is what a torch dimming looks like, as opposed to a pulse (which is a few percent and oscillates).
     */
    fun torchDimmed(red: DoubleArray, fs: Double, stepFraction: Double = 0.12): Boolean {
        val block = fs.toInt()
        if (block < 5 || red.size < 4 * block) return false
        val means = (0 until red.size / block).map { b -> (b * block until (b + 1) * block).sumOf { red[it] } / block }
        for (i in 1 until means.size - 1) {
            val before = means[i - 1]
            if (before <= 1e-9) continue
            val jump = abs(means[i] - before) / before
            val stays = abs(means[i + 1] - before) / before > stepFraction * 0.7
            if (jump > stepFraction && stays) return true
        }
        return false
    }
}
