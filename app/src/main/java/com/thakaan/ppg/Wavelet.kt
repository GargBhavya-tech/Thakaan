package com.thakaan.ppg

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.sqrt

/** Daubechies-4 discrete wavelet transform with periodic extension (orthogonal, so the inverse is the transpose). */
object Wavelet {
    private val s3 = sqrt(3.0)
    private val h = doubleArrayOf(
        (1 + s3) / (4 * sqrt(2.0)), (3 + s3) / (4 * sqrt(2.0)), (3 - s3) / (4 * sqrt(2.0)), (1 - s3) / (4 * sqrt(2.0)),
    )
    private val g = doubleArrayOf(h[3], -h[2], h[1], -h[0])

    /** One level: approximation and detail, each half the length. [x].size must be even. */
    fun forward(x: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = x.size
        require(n % 2 == 0 && n >= 4)
        val a = DoubleArray(n / 2)
        val d = DoubleArray(n / 2)
        for (k in 0 until n / 2) {
            var sa = 0.0; var sd = 0.0
            for (j in 0..3) {
                val v = x[(2 * k + j) % n]
                sa += h[j] * v
                sd += g[j] * v
            }
            a[k] = sa; d[k] = sd
        }
        return a to d
    }

    fun inverse(a: DoubleArray, d: DoubleArray): DoubleArray {
        val n = a.size * 2
        val x = DoubleArray(n)
        for (k in a.indices) for (j in 0..3) x[(2 * k + j) % n] += h[j] * a[k] + g[j] * d[k]
        return x
    }

    /**
     * Soft-threshold denoising. Noise sigma comes from the finest detail band (median absolute deviation / 0.6745) and the
     * threshold is the universal sigma * sqrt(2 ln N). Only the [levels] finest detail bands are thresholded, so the
     * band-passed pulse (below ~3 Hz at 100 Hz sampling) keeps its shape.
     */
    fun denoise(x: DoubleArray, levels: Int = 3, thresholdedLevels: Int = 2): DoubleArray {
        val n0 = x.size
        val block = 1 shl levels
        if (n0 < block * 4) return x.copyOf()
        val padded = (n0 + block - 1) / block * block
        // reflect padding at the tail
        val xp = DoubleArray(padded) { if (it < n0) x[it] else x[(2 * n0 - 2 - it).coerceIn(0, n0 - 1)] }

        val details = ArrayList<DoubleArray>()
        var approx = xp
        repeat(levels) {
            val (a, d) = forward(approx)
            details += d
            approx = a
        }
        val sigma = Dsp.median(DoubleArray(details[0].size) { abs(details[0][it]) }) / 0.6745
        val thr = sigma * sqrt(2.0 * ln(padded.toDouble()))
        for (lvl in 0 until minOf(thresholdedLevels, levels)) {
            val d = details[lvl]
            for (i in d.indices) d[i] = softThreshold(d[i], thr)
        }
        var rec = approx
        for (lvl in levels - 1 downTo 0) rec = inverse(rec, details[lvl])
        return rec.copyOf(n0)
    }

    private fun softThreshold(v: Double, t: Double): Double = when {
        v > t -> v - t
        v < -t -> v + t
        else -> 0.0
    }
}
