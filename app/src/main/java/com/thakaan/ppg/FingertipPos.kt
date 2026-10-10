package com.thakaan.ppg

import com.thakaan.face.FaceRppg
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Result of the colour-combining fingertip pulse estimate. [windowHrs] are the per-window estimates the decision rests on,
 * so a rejection can say why ("the reading kept changing") and a test can see it.
 */
class FingertipPosResult(
    val accepted: Boolean,
    val hrBpm: Double?,
    /** Share of the sliding windows whose estimate is within a few bpm of the median. */
    val consistentFraction: Double,
    val windowHrs: List<Double>,
    val reason: String?,
    /** Median of the window rates even when the capture was rejected, for display only ("best guess, not trusted"). */
    val estimateBpm: Double? = null,
)

/**
 * A second way to read the fingertip pulse, built from what real captures on the iQOO 15 showed: with the finger resting
 * lightly the pulse is only a few hundredths of a percent of the light level, too small for the red-only test, yet it is
 * there, at the same rate, in every colour view. Hand pressure and small movements add much larger slow changes that are
 * common to all colours. So:
 *  1. combine red, green and blue with POS (the projection that cancels changes common to all colours),
 *  2. read the pulse rate in overlapping 8 s windows,
 *  3. accept only if the estimate stays consistent from window to window AND the whole-capture estimate agrees with at
 *     least one independent colour view (green minus blue, or green minus red). On the real table capture the blue-only
 *     view did NOT agree, so it is not used: a cross-check that fails on the best real data is not a safeguard.
 * A pulse that wanders, or a periodic disturbance that appears in only one view, is rejected, which is the safe side:
 * a wrong accepted pulse is worse than a retry. Thresholds are from four real captures plus synthetic tests, not yet from
 * a reference heart rate, and must be re-checked against one (docs/DEVICE_RESULTS.md).
 */
object FingertipPos {
    const val FS = 30.0
    const val WINDOW_SEC = 8.0
    const val STEP_SEC = 2.0
    const val CONSISTENT_TOLERANCE_BPM = 3.0
    const val MIN_CONSISTENT_FRACTION = 0.75
    const val MIN_WINDOWS = 6
    const val VIEW_TOLERANCE_BPM = 5.0
    const val MIN_HR = 45.0
    const val MAX_HR = 170.0

    /** Null when the frames carry no blue channel (older captures), so the caller falls back to the red-only method. */
    fun analyse(frames: List<PpgFrame>): FingertipPosResult? {
        if (frames.size < 90 || frames.sumOf { it.blue } / frames.size < 1.0) return null
        return try { run(frames) } catch (_: IllegalArgumentException) { null }
    }

    private fun run(frames: List<PpgFrame>): FingertipPosResult {
        val t0 = frames.first().tNanos
        val t = DoubleArray(frames.size) { (frames[it].tNanos - t0) / 1e9 }
        val r = Dsp.resampleUniform(t, DoubleArray(frames.size) { frames[it].red }, FS).second
        val g = Dsp.resampleUniform(t, DoubleArray(frames.size) { frames[it].green }, FS).second
        val b = Dsp.resampleUniform(t, DoubleArray(frames.size) { frames[it].blue }, FS).second

        val trim = (FaceRppg.POS_WINDOW_SEC * FS).roundToInt()
        fun core(x: DoubleArray): DoubleArray = if (x.size > 4 * trim) x.copyOfRange(trim, x.size - trim) else x
        val pos = core(Dsp.bandpass(FaceRppg.pos(r, g, b, FS), FS, 0.7, 3.0))

        val win = (WINDOW_SEC * FS).roundToInt(); val step = (STEP_SEC * FS).roundToInt()
        val hrs = ArrayList<Double>()
        var a = 0
        while (a + win <= pos.size) { peakBpm(pos.copyOfRange(a, a + win))?.let { hrs += it }; a += step }
        if (hrs.size < MIN_WINDOWS) return FingertipPosResult(false, null, 0.0, hrs, "too few windows")

        val sorted = hrs.sorted()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else 0.5 * (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2])
        val frac = hrs.count { abs(it - median) <= CONSISTENT_TOLERANCE_BPM }.toDouble() / hrs.size
        if (median !in MIN_HR..MAX_HR) return FingertipPosResult(false, null, frac, hrs, "rate out of range", median)
        if (frac < MIN_CONSISTENT_FRACTION) return FingertipPosResult(false, null, frac, hrs, "the estimate kept changing", median)

        // whole-capture agreement with the POS result and with two independent colour views
        val whole = peakBpm(pos) ?: return FingertipPosResult(false, null, frac, hrs, "no clear pulse", median)
        if (abs(whole - median) > CONSISTENT_TOLERANCE_BPM) return FingertipPosResult(false, null, frac, hrs, "whole capture disagrees", median)
        val mr = r.average(); val mg = g.average(); val mb = b.average()
        // two independent projections that both carry the pulse well: green minus blue and green minus red (each normalised)
        val gMinusB = DoubleArray(r.size) { g[it] / mg - b[it] / mb }
        val gMinusR = DoubleArray(r.size) { -(g[it] / mg - r[it] / mr) }
        val viewGb = peakBpm(core(Dsp.bandpass(gMinusB, FS, 0.7, 3.0)))
        val viewGr = peakBpm(core(Dsp.bandpass(gMinusR, FS, 0.7, 3.0)))
        val agree = listOf(viewGb, viewGr).count { it != null && abs(it - median) <= VIEW_TOLERANCE_BPM }
        if (agree < 1) return FingertipPosResult(false, null, frac, hrs, "the colour views disagree", median)
        return FingertipPosResult(true, median, frac, hrs, null, median)
    }

    /**
     * A running estimate for the screen while the finger is on the lens: POS over the last [seconds] seconds. DISPLAY ONLY,
     * never fed to the risk engine and never called accepted. Null when there is no blue channel or too little data.
     */
    fun liveBpm(frames: List<PpgFrame>, seconds: Double = 10.0): Double? {
        if (frames.size < 90) return null
        val tEnd = frames.last().tNanos
        val recent = frames.filter { (tEnd - it.tNanos) / 1e9 <= seconds }
        if (recent.size < 90 || recent.sumOf { it.blue } / recent.size < 1.0) return null
        return try {
            val t0 = recent.first().tNanos
            val t = DoubleArray(recent.size) { (recent[it].tNanos - t0) / 1e9 }
            val r = Dsp.resampleUniform(t, DoubleArray(recent.size) { recent[it].red }, FS).second
            val g = Dsp.resampleUniform(t, DoubleArray(recent.size) { recent[it].green }, FS).second
            val b = Dsp.resampleUniform(t, DoubleArray(recent.size) { recent[it].blue }, FS).second
            val trim = (FaceRppg.POS_WINDOW_SEC * FS).roundToInt()
            val x = Dsp.bandpass(FaceRppg.pos(r, g, b, FS), FS, 0.7, 3.0)
            peakBpm(if (x.size > 4 * trim) x.copyOfRange(trim, x.size - trim) else x)
        } catch (_: IllegalArgumentException) { null }
    }

    /** Strongest spectral line between 45 and 170 bpm, on a 0.6 bpm grid. */
    private fun peakBpm(x: DoubleArray): Double? {
        if (x.size < FS * 4) return null
        val freqs = DoubleArray(((3.0 - 0.75) / 0.01).roundToInt() + 1) { 0.75 + 0.01 * it }
        val p = Dsp.powerAt(x, FS, freqs)
        var best = 0
        for (i in p.indices) if (p[i] > p[best]) best = i
        return if (p[best] <= 0.0) null else freqs[best] * 60.0
    }
}
