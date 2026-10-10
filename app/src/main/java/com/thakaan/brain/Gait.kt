package com.thakaan.brain

import kotlin.math.sqrt

class GaitResult(
    /** Coefficient of variation of step intervals (stride-time variability). Higher = less steady. */
    val strideCv: Double,
    /** Height of the main autocorrelation peak at the step lag (1 = perfectly periodic). */
    val acfPeak: Double,
    /** RMS of horizontal (side-to-side + fore-aft) acceleration, m/s^2. */
    val swayRms: Double,
    val steps: Int,
    val valid: Boolean,
    val reason: String = "",
)

/** N1: gait steadiness on every walk. Baselines are per phone placement; pocket and hand gait never mix. */
object Gait {
    /** Split raw acceleration into the part along gravity and the horizontal remainder (magnitude). */
    fun project(ax: DoubleArray, ay: DoubleArray, az: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val gx = ax.average(); val gy = ay.average(); val gz = az.average()
        val gn = sqrt(gx * gx + gy * gy + gz * gz).coerceAtLeast(1e-9)
        val ux = gx / gn; val uy = gy / gn; val uz = gz / gn
        val vert = DoubleArray(ax.size); val lat = DoubleArray(ax.size)
        for (i in ax.indices) {
            val dx = ax[i] - gx; val dy = ay[i] - gy; val dz = az[i] - gz
            val v = dx * ux + dy * uy + dz * uz
            vert[i] = v
            val hx = dx - v * ux; val hy = dy - v * uy; val hz = dz - v * uz
            lat[i] = sqrt(hx * hx + hy * hy + hz * hz)
        }
        return vert to lat
    }

    fun analyse(vert: DoubleArray, lateral: DoubleArray, fs: Double): GaitResult {
        val n = vert.size
        if (fs < 25.0) return GaitResult(0.0, 0.0, 0.0, 0, false, "sample rate too low")
        if (n / fs < 20.0) return GaitResult(0.0, 0.0, 0.0, 0, false, "needs about 20 s of continuous walking")
        val mean = vert.average()
        val v = DoubleArray(n) { vert[it] - mean }
        val sd = sqrt(v.sumOf { it * it } / n)
        if (sd < 0.3) return GaitResult(0.0, 0.0, 0.0, 0, false, "not walking")

        // light smoothing then local maxima above 0.5 sd with a 0.3 s refractory period
        val sm = DoubleArray(n) { i -> var s = 0.0; var c = 0; for (j in maxOf(0, i - 2)..minOf(n - 1, i + 2)) { s += v[j]; c++ }; s / c }
        val refractory = (0.3 * fs).toInt()
        val peaks = ArrayList<Int>()
        var i = 1
        while (i < n - 1) {
            if (sm[i] > 0.5 * sd && sm[i] >= sm[i - 1] && sm[i] > sm[i + 1]) {
                if (peaks.isEmpty() || i - peaks.last() >= refractory) peaks += i
                else if (sm[i] > sm[peaks.last()]) peaks[peaks.size - 1] = i
            }
            i++
        }
        if (peaks.size < 12) return GaitResult(0.0, 0.0, 0.0, peaks.size, false, "too few steps found")
        val intervals = (1 until peaks.size).map { (peaks[it] - peaks[it - 1]) / fs }
        val mi = intervals.average()
        val cv = sqrt(intervals.sumOf { (it - mi) * (it - mi) } / (intervals.size - 1)) / mi

        // autocorrelation peak near the mean step lag
        val lagC = (mi * fs).toInt()
        var best = 0.0
        val denom = v.sumOf { it * it }
        for (lag in (lagC * 0.7).toInt().coerceAtLeast(1)..(lagC * 1.3).toInt().coerceAtMost(n / 2)) {
            var s = 0.0
            for (k in 0 until n - lag) s += v[k] * v[k + lag]
            best = maxOf(best, s / denom)
        }
        val sway = if (lateral.isEmpty()) 0.0 else {
            val lm = lateral.average()
            sqrt(lateral.sumOf { (it - lm) * (it - lm) } / lateral.size)
        }
        return GaitResult(cv, best, sway, peaks.size, true)
    }

    fun toResults(r: GaitResult, placement: String, tNanos: Long, hour: Double, device: String): List<BrainResult> {
        if (!r.valid || placement.isBlank() || placement == "UNKNOWN") return emptyList() // never score without a known placement
        return listOf(
            BrainResult(BrainTest.GAIT_STRIDE_CV, r.strideCv, tNanos, hour, device, placement),
            BrainResult(BrainTest.GAIT_SWAY, r.swayRms, tNanos, hour, device, placement),
        )
    }
}
