package com.thakaan.ppg

import kotlin.math.abs
import kotlin.math.log10

/**
 * Why a capture was rejected, in priority order, each with the one thing the worker should do about it.
 * A silent failure or a wrong number is worse than a retry prompt (ticket #10).
 */
enum class Reason(val prompt: String) {
    NO_FINGER("Cover the camera and torch with your fingertip"),
    SATURATED("Shade the phone"),
    DROPPED_FRAMES("Hold still and try again"),
    TOO_SHORT("Keep your finger on the lens a little longer"),
    LOW_PERFUSION("Press lighter and keep your finger still"),
    NOISY("Wipe your finger and the lens"),
    MOTION("Hold still"),
    UNSTEADY("The reading kept changing: rest your arm on a table, rest your fingertip lightly on the lens, and keep still"),
    HR_DISAGREE("Hold still and try again"),
}

data class Thresholds(
    val minPerfusionPct: Double = 0.3,
    val minTemplateCorrelation: Double = 0.85,
    val minSnrDb: Double = 5.0,
    val maxDroppedFraction: Double = 0.02,
    val minRawMean: Double = 30.0,
    val maxSaturatedFraction: Double = 0.2,
    val minSeconds: Double = 8.0,
    val maxHrDisagreeBpm: Double = 8.0,
)

class QualityReport(
    val perfusionIndexPct: Double,
    val templateCorrelation: Double,
    val snrDb: Double,
    val droppedFraction: Double,
    val reasons: List<Reason>,
) {
    val accepted: Boolean get() = reasons.isEmpty()
    /** The single most important thing to tell the worker, or null if the window is good. */
    val prompt: String? get() = reasons.minByOrNull { it.ordinal }?.prompt
}

object Quality {
    /** Perfusion index: pulsatile (AC) part as a percentage of the steady (DC) light level. */
    fun perfusionIndexPct(a: PpgAnalysis): Double =
        if (a.meanRaw <= 1e-9) 0.0 else Dsp.robustRange(a.filtered) / a.meanRaw * 100.0

    /**
     * Mean correlation of each beat with the average beat. Beats are cut around the peaks at a fixed length (the median
     * interval), resampled to 40 points and z-scored, so slow heart-rate variability does not look like a bad template.
     */
    fun templateCorrelation(a: PpgAnalysis): Double {
        val p = a.peakIdx
        if (p.size < 6) return 0.0
        val gaps = DoubleArray(p.size - 1) { p[it + 1] - p[it] }
        val t = Dsp.median(gaps)
        val points = 40
        val beats = ArrayList<DoubleArray>()
        for (i in 1 until p.size - 1) {
            val start = p[i] - 0.4 * t
            val end = p[i] + 0.6 * t
            if (start < 0 || end >= a.filtered.size - 1) continue
            val b = DoubleArray(points) { k ->
                val pos = start + (end - start) * k / (points - 1)
                val i0 = pos.toInt()
                a.filtered[i0] + (a.filtered[i0 + 1] - a.filtered[i0]) * (pos - i0)
            }
            val m = Dsp.mean(b)
            val s = Dsp.std(b)
            if (s <= 1e-12) return 0.0
            beats += DoubleArray(points) { (b[it] - m) / s }
        }
        if (beats.size < 4) return 0.0
        val template = DoubleArray(points) { k -> beats.sumOf { it[k] } / beats.size }
        val tm = Dsp.mean(template)
        val ts = Dsp.std(template)
        if (ts <= 1e-12) return 0.0
        val corrs = beats.map { b ->
            var acc = 0.0
            for (k in 0 until points) acc += b[k] * (template[k] - tm) / ts
            acc / (points - 1)
        }
        return corrs.average()
    }

    /** Power near the heart rate and its harmonics versus the rest of 0.5 to 4 Hz, in dB. */
    fun snrDb(a: PpgAnalysis): Double {
        val hr = a.hrBpm ?: return -99.0
        val f0 = hr / 60.0
        val freqs = DoubleArray(176) { 0.5 + 0.02 * it } // 0.5 .. 4.0 Hz
        val power = Dsp.powerAt(a.filtered, a.fs, freqs)
        var signal = 0.0
        var noise = 0.0
        for (i in freqs.indices) {
            val f = freqs[i]
            val nearHarmonic = (1..3).any { abs(f - it * f0) <= 0.15 }
            if (nearHarmonic) signal += power[i] else noise += power[i]
        }
        return 10.0 * log10((signal + 1e-30) / (noise + 1e-30))
    }

    fun evaluate(a: PpgAnalysis, th: Thresholds = Thresholds()): QualityReport {
        val reasons = ArrayList<Reason>()
        val droppedFraction = a.droppedFrames.toDouble() / a.frameCount
        val pi = perfusionIndexPct(a)

        if (a.meanRaw < th.minRawMean) reasons += Reason.NO_FINGER
        if (a.saturatedFraction > th.maxSaturatedFraction || a.meanRaw > 250.0) reasons += Reason.SATURATED
        if (droppedFraction > th.maxDroppedFraction) reasons += Reason.DROPPED_FRAMES
        if (a.durationSec < th.minSeconds) reasons += Reason.TOO_SHORT

        var corr = 0.0
        var snr = -99.0
        // The signal-shape gates only mean something once there is light and enough data.
        if (Reason.NO_FINGER !in reasons && Reason.TOO_SHORT !in reasons) {
            if (pi < th.minPerfusionPct) reasons += Reason.LOW_PERFUSION
            corr = templateCorrelation(a)
            snr = snrDb(a)
            if (snr < th.minSnrDb) reasons += Reason.NOISY
            if (corr < th.minTemplateCorrelation) reasons += Reason.MOTION
            val spectral = a.hrBpm
            val beats = a.peakHrBpm
            if (spectral != null && (beats == null || abs(spectral - beats) > th.maxHrDisagreeBpm)) reasons += Reason.HR_DISAGREE
        }
        return QualityReport(pi, corr, snr, droppedFraction, reasons)
    }
}
