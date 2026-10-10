package com.thakaan.ml

import kotlin.math.abs
import kotlin.math.sqrt

class ScgBeats(val bpm: Double?, val beatTimesSec: List<Double>, val reason: String?)

/**
 * Chest seismocardiography (P3, ticket #30): after the learned denoiser has cleaned 5 s of chest acceleration, find the
 * aortic-opening bursts and read the heart rate from their spacing. This part is plain DSP and is tested without the model.
 * Display / cross-check only (like the face pulse): it never reaches the risk engine.
 */
object ScgHeart {
    const val FS = 100.0

    /** Window length the denoiser takes. */
    const val N = 500

    /** Zero-mean, unit-variance copy, so the denoiser sees the scale it was trained on. */
    fun normalise(x: DoubleArray): DoubleArray {
        val m = x.average()
        val s = sqrt(x.sumOf { (it - m) * (it - m) } / x.size).coerceAtLeast(1e-9)
        return DoubleArray(x.size) { (x[it] - m) / s }
    }

    /** Heart rate from a cleaned signal: peaks of the smoothed absolute value, at least 0.33 s apart, median spacing. */
    fun beats(clean: DoubleArray): ScgBeats {
        if (clean.size < 200) return ScgBeats(null, emptyList(), "too short")
        val env = DoubleArray(clean.size)
        val w = 4 // 40 ms smoothing
        for (i in clean.indices) {
            var s = 0.0; var c = 0
            for (j in (i - w).coerceAtLeast(0)..(i + w).coerceAtMost(clean.size - 1)) { s += abs(clean[j]); c++ }
            env[i] = s / c
        }
        val mean = env.average()
        val thr = mean + 0.8 * sqrt(env.sumOf { (it - mean) * (it - mean) } / env.size)
        val minGap = (0.33 * FS).toInt()
        val peaks = ArrayList<Int>()
        for (i in 1 until env.size - 1) {
            if (env[i] >= thr && env[i] >= env[i - 1] && env[i] > env[i + 1]) {
                if (peaks.isEmpty() || i - peaks.last() >= minGap) peaks += i
                else if (env[i] > env[peaks.last()]) peaks[peaks.size - 1] = i
            }
        }
        if (peaks.size < 4) return ScgBeats(null, peaks.map { it / FS }, "too few beats")
        val gaps = peaks.zipWithNext { a, b -> (b - a) / FS }.sorted()
        val med = gaps[gaps.size / 2]
        // beats must be regular: most gaps close to the median
        val regular = gaps.count { abs(it - med) <= 0.15 * med }.toDouble() / gaps.size
        if (regular < 0.6) return ScgBeats(null, peaks.map { it / FS }, "beats not regular")
        val bpm = 60.0 / med
        return if (bpm in 40.0..180.0) ScgBeats(bpm, peaks.map { it / FS }, null) else ScgBeats(null, peaks.map { it / FS }, "rate out of range")
    }
}
