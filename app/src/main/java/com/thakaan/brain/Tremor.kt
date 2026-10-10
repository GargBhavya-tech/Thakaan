package com.thakaan.brain

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class TremorResult(
    /** Mean spectral density of acceleration in the band, (m/s^2)^2 per Hz. */
    val bandPower: Double,
    /** Band power as a share of all power from 0.5 Hz up (postural sway sits below the band). */
    val bandShare: Double,
    val durationSec: Double,
    val liftingConfound: Boolean,
    val valid: Boolean,
    val reason: String = "",
)

/**
 * N9 tremor: PSD of the (gravity-free) acceleration magnitude in 4 to 12 Hz while the phone is held out at arm's length
 * (or passively whenever it is already in hand). Tests within 60 s of a lifting label are flagged, not scored.
 */
object Tremor {
    const val BAND_LO = 4.0
    const val BAND_HI = 12.0
    const val LIFT_CONFOUND_NANOS = 60_000_000_000L

    fun analyse(ax: DoubleArray, ay: DoubleArray, az: DoubleArray, fs: Double, lastLiftingNanos: Long?, nowNanos: Long): TremorResult {
        val n = ax.size
        val dur = if (fs > 0) n / fs else 0.0
        val confound = lastLiftingNanos != null && nowNanos - lastLiftingNanos in 0..LIFT_CONFOUND_NANOS
        if (fs < 50.0) return TremorResult(0.0, 0.0, dur, confound, false, "sample rate below 50 Hz cannot see 12 Hz")
        if (dur < 10.0) return TremorResult(0.0, 0.0, dur, confound, false, "needs at least 10 s of holding still")
        if (ay.size != n || az.size != n) return TremorResult(0.0, 0.0, dur, confound, false, "axis lengths differ")

        val mag = DoubleArray(n) { sqrt(ax[it] * ax[it] + ay[it] * ay[it] + az[it] * az[it]) }
        val mean = mag.average()
        val w = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) } // Hann
        val x = DoubleArray(n) { (mag[it] - mean) * w[it] }
        val wPow = w.sumOf { it * it }
        var band = 0.0; var bandN = 0; var all = 0.0
        // direct DFT on a 0.25 Hz grid: at most ~100 bins x a few thousand samples, fine on a phone and in tests
        var f = 0.5
        while (f <= minOf(fs / 2, 25.0)) {
            var re = 0.0; var im = 0.0
            val wk = 2 * PI * f / fs
            for (i in 0 until n) { re += x[i] * cos(wk * i); im -= x[i] * sin(wk * i) }
            val psd = (re * re + im * im) / (fs * wPow)
            val one = 2 * psd
            all += one
            if (f in BAND_LO..BAND_HI) { band += one; bandN++ }
            f += 0.25
        }
        val power = if (bandN > 0) band / bandN else 0.0
        val share = if (all > 0) band / all else 0.0
        return TremorResult(power, share, dur, confound, true)
    }

    /** Value to feed the CUSUM, or null when the test must not be scored. A confounded test is stored with the flag. */
    fun toResult(r: TremorResult, tNanos: Long, hour: Double, device: String): BrainResult? {
        if (!r.valid) return null
        return BrainResult(BrainTest.TREMOR, r.bandPower, tNanos, hour, device, "", if (r.liftingConfound) setOf("lifting_confound") else emptySet())
    }
}
