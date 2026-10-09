package com.thakaan.ppg

import kotlin.math.abs
import kotlin.math.ln

data class WindowHr(val centreSec: Double, val bpm: Double, val confidence: Double)

object PeakTracking {
    /**
     * Beat peaks from the first derivative: a peak is where the slope turns from positive to non-positive.
     * - [refractoryMs]: after accepting a peak, ignore anything closer (the dicrotic notch would otherwise double count).
     *   When two candidates fall inside the refractory window the taller one wins.
     * - Prominence: the peak must stand [minProminenceFrac] of the signal's robust range above the trough before it.
     * Returned positions are fractional sample indices (parabolic interpolation), which gives millisecond-level beat timing at 100 Hz.
     */
    fun detectPeaks(x: DoubleArray, fs: Double, refractoryMs: Double = 300.0, minProminenceFrac: Double = 0.35): DoubleArray {
        if (x.size < 5) return DoubleArray(0)
        val amp = Dsp.robustRange(x)
        if (amp <= 0.0) return DoubleArray(0)
        val refractory = refractoryMs / 1000.0 * fs
        val troughWindow = (0.5 * fs).toInt()
        val kept = ArrayList<Int>()
        for (i in 1 until x.size - 1) {
            val rising = x[i] - x[i - 1] > 0
            val falling = x[i + 1] - x[i] <= 0
            if (!(rising && falling)) continue
            var trough = x[i]
            for (j in maxOf(0, i - troughWindow) until i) if (x[j] < trough) trough = x[j]
            if (x[i] - trough < minProminenceFrac * amp) continue
            val last = kept.lastOrNull()
            if (last != null && i - last < refractory) {
                if (x[i] > x[last]) kept[kept.size - 1] = i
            } else {
                kept += i
            }
        }
        return DoubleArray(kept.size) { k ->
            val i = kept[k]
            val y0 = x[i - 1]; val y1 = x[i]; val y2 = x[i + 1]
            val denom = y0 - 2 * y1 + y2
            i + if (abs(denom) < 1e-12) 0.0 else 0.5 * (y0 - y2) / denom
        }
    }

    /** Inter-beat intervals in ms, keeping only physiologically plausible ones (300 to 1500 ms). */
    fun ibiMs(peakIdx: DoubleArray, fs: Double): DoubleArray {
        val out = ArrayList<Double>()
        for (i in 1 until peakIdx.size) {
            val ms = (peakIdx[i] - peakIdx[i - 1]) / fs * 1000.0
            if (ms in 300.0..1500.0) out += ms
        }
        return out.toDoubleArray()
    }

    /**
     * Heart rate per sliding window with Viterbi tracking. Per-window spectral peaks jump between the beat rate and its
     * harmonics; picking, for each window, the candidate that keeps the whole path smooth removes those jumps.
     */
    fun trackHr(
        x: DoubleArray,
        fs: Double,
        windowSec: Double = 8.0,
        stepSec: Double = 1.0,
        minBpm: Double = 40.0,
        maxBpm: Double = 200.0,
        candidatesPerWindow: Int = 5,
        jumpSigmaBpm: Double = 4.0,
    ): List<WindowHr> {
        val win = minOf((windowSec * fs).toInt(), x.size)
        if (win < (3 * fs).toInt()) return emptyList()
        val step = (stepSec * fs).toInt().coerceAtLeast(1)
        val freqsBpm = DoubleArray(((maxBpm - minBpm) / 0.5).toInt() + 1) { minBpm + 0.5 * it }
        val freqsHz = DoubleArray(freqsBpm.size) { freqsBpm[it] / 60.0 }

        class Cand(val bpm: Double, val power: Double)

        val starts = ArrayList<Int>()
        var s = 0
        while (s + win <= x.size) { starts += s; s += step }
        if (starts.isEmpty()) starts += 0

        val cands = starts.map { st ->
            val seg = x.copyOfRange(st, minOf(st + win, x.size))
            val p = Dsp.powerAt(seg, fs, freqsHz)
            val peaks = ArrayList<Cand>()
            for (k in 1 until p.size - 1) if (p[k] > p[k - 1] && p[k] >= p[k + 1]) peaks += Cand(freqsBpm[k], p[k])
            peaks.sortByDescending { it.power }
            peaks.take(candidatesPerWindow).ifEmpty { listOf(Cand(freqsBpm[p.indices.maxByOrNull { p[it] } ?: 0], p.maxOrNull() ?: 0.0)) }
        }

        // Viterbi: minimise sum of emission cost (weak peak = expensive) + jump cost (large change = expensive).
        val n = cands.size
        val cost = Array(n) { DoubleArray(cands[it].size) }
        val back = Array(n) { IntArray(cands[it].size) }
        val maxP = cands.map { c -> c.maxOf { it.power } }
        fun emission(t: Int, k: Int) = -ln(cands[t][k].power / (maxP[t] + 1e-30) + 1e-3)
        for (k in cands[0].indices) cost[0][k] = emission(0, k)
        for (t in 1 until n) {
            for (k in cands[t].indices) {
                var best = Double.MAX_VALUE
                var arg = 0
                for (j in cands[t - 1].indices) {
                    val d = cands[t][k].bpm - cands[t - 1][j].bpm
                    val c = cost[t - 1][j] + d * d / (2 * jumpSigmaBpm * jumpSigmaBpm)
                    if (c < best) { best = c; arg = j }
                }
                cost[t][k] = best + emission(t, k)
                back[t][k] = arg
            }
        }
        var k = cost[n - 1].indices.minByOrNull { cost[n - 1][it] } ?: 0
        val path = IntArray(n)
        for (t in n - 1 downTo 0) { path[t] = k; k = back[t][k] }

        return List(n) { t ->
            val c = cands[t][path[t]]
            val conf = (c.power / (maxP[t] + 1e-30)).coerceIn(0.0, 1.0)
            WindowHr((starts[t] + win / 2.0) / fs, c.bpm, conf)
        }
    }
}
