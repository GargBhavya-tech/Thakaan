package com.thakaan.ppg

import kotlin.math.sqrt

/**
 * What to draw while a fingertip is on the lens. The raw brightness drifts by far more than a heartbeat (pressure, blood
 * pooling), so a graph of it looks like a slow slope and the beats are invisible. This shows the pulse component instead:
 * inverted (more blood absorbs more light, so the raw level goes DOWN with each beat), minus a 1 s moving average, lightly smoothed.
 */
object LiveWave {
    fun pulse(x: List<Double>): List<Float> {
        if (x.size < 40) return emptyList()
        val inv = x.map { -it }
        val w = 30
        val hp = inv.indices.map { i ->
            val a = (i - w / 2).coerceAtLeast(0); val b = (i + w / 2).coerceAtMost(inv.lastIndex)
            inv[i] - inv.subList(a, b + 1).average()
        }
        return hp.indices.map { i -> hp.subList((i - 1).coerceAtLeast(0), (i + 2).coerceAtMost(hp.size)).average().toFloat() }
    }

    /** Size of the pulse component as a percentage of the steady level over the last few seconds. */
    fun strengthPct(x: List<Double>): Double {
        if (x.size < 40) return 0.0
        val p = pulse(x).takeLast(120)
        val mean = x.takeLast(120).average()
        if (mean < 1e-6) return 0.0
        val m = p.average()
        return sqrt(p.sumOf { ((it - m) * (it - m)).toDouble() } / p.size) / mean * 100.0
    }
}
