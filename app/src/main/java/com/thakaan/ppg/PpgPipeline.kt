package com.thakaan.ppg

/** One camera frame reduced to the mean colour of the centre patch. [tNanos] is the sensor (hardware) timestamp. */
class PpgFrame(val tNanos: Long, val red: Double, val green: Double = 0.0, val blue: Double = 0.0)

class PpgAnalysis(
    val fs: Double,
    val timesSec: DoubleArray,
    /** Band-passed, denoised, pulse-positive signal (blood volume up = value up). */
    val filtered: DoubleArray,
    val peakIdx: DoubleArray,
    val ibiMs: DoubleArray,
    val hrTrack: List<WindowHr>,
    /** Heart rate from the Viterbi-tracked spectrum (median of the last few windows). Null if too little data. */
    val hrBpm: Double?,
    /** Heart rate from beat intervals; used to cross-check [hrBpm]. */
    val peakHrBpm: Double?,
    val droppedFrames: Int,
    val frameCount: Int,
    val durationSec: Double,
    val meanRaw: Double,
    val saturatedFraction: Double,
)

object PpgPipeline {
    const val DEFAULT_FS = 100.0

    /**
     * Frames to heart rate:
     * cubic-spline resample to a uniform grid, zero-phase 0.5 to 4.0 Hz band-pass, Daubechies-4 wavelet denoise,
     * first-derivative peak detection with a refractory period, Viterbi-tracked windowed heart rate.
     *
     * Finger PPG with a torch: more blood means more absorption, so the raw red mean goes DOWN with each pulse.
     * The signal is inverted so that a beat is a positive peak.
     */
    fun analyse(frames: List<PpgFrame>, fs: Double = DEFAULT_FS): PpgAnalysis {
        require(frames.size >= 30) { "need at least a second of frames" }
        val t = DoubleArray(frames.size) { (frames[it].tNanos - frames[0].tNanos) / 1e9 }
        val raw = DoubleArray(frames.size) { frames[it].red }

        val dts = DoubleArray(t.size - 1) { t[it + 1] - t[it] }
        val medDt = Dsp.median(dts)
        // Estimated missing frames: a gap of k frame-intervals means k - 1 frames were lost.
        val dropped = dts.filter { it > 1.5 * medDt }.sumOf { (kotlin.math.round(it / medDt).toInt() - 1).coerceAtLeast(1) }
        val saturated = raw.count { it >= 253.0 }.toDouble() / raw.size

        val (tg, yg) = Dsp.resampleUniform(t, raw, fs)
        val inverted = DoubleArray(yg.size) { -yg[it] }
        val band = Dsp.bandpass(inverted, fs)
        val clean = Wavelet.denoise(band)

        val track = PeakTracking.trackHr(clean, fs)
        // The capture's heart rate is the median over all windows: the last few windows follow beat-to-beat variability.
        val hr = track.map { it.bpm }.toDoubleArray().let { if (it.isEmpty()) null else Dsp.median(it) }

        // Refractory adapts to the tracked rate so the dicrotic notch is not counted at slow heart rates.
        val refractory = if (hr == null) 300.0 else maxOf(300.0, 0.55 * 60_000.0 / hr)
        val peaks = PeakTracking.detectPeaks(clean, fs, refractoryMs = refractory)
        val ibi = PeakTracking.ibiMs(peaks, fs)
        val peakHr = if (ibi.size >= 3) 60_000.0 / Dsp.median(ibi) else null

        return PpgAnalysis(
            fs = fs, timesSec = tg, filtered = clean, peakIdx = peaks, ibiMs = ibi, hrTrack = track,
            hrBpm = hr, peakHrBpm = peakHr, droppedFrames = dropped, frameCount = frames.size,
            durationSec = t.last(), meanRaw = Dsp.mean(raw), saturatedFraction = saturated,
        )
    }
}
