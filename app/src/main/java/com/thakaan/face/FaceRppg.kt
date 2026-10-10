package com.thakaan.face

import com.thakaan.ppg.Dsp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * One front-camera frame, reduced to what the pulse needs. [r], [g], [b] are the mean colour over the skin region
 * (forehead and cheeks) in 0..255; [faceFound] is false when no face was tracked in that frame. [motion] is how far the
 * region moved since the previous frame as a fraction of the face width (0 when unknown). No image is ever kept (invariant 7).
 */
class FaceFrame(val tNanos: Long, val r: Double, val g: Double, val b: Double, val faceFound: Boolean, val motion: Double = 0.0)

enum class FaceReason(val prompt: String) {
    TOO_SHORT("Hold still a little longer"),
    NO_FACE("Keep your face in the frame"),
    TOO_DARK("Too dark: face the light"),
    TOO_BRIGHT("Too bright: step out of direct sun"),
    MOVING("Keep your head still"),
    NO_PULSE("No clear pulse in the image"),
    UNEVEN_FRAMES("The camera dropped frames"),
}

/**
 * Starting gates. A pulse accepted from a face when it is wrong is worse than a retry, so these lean strict; they are
 * not calibrated on real faces yet and must be set from data next to a reference (see docs/FACE_DESIGN.md).
 */
data class FaceThresholds(
    val minSeconds: Double = 10.0,
    val minFaceFraction: Double = 0.8,
    val minChannelMean: Double = 40.0,
    val maxChannelMean: Double = 235.0,
    val maxMedianMotion: Double = 0.02,
    val minSnrDb: Double = 4.0,
    val minHrBpm: Double = 45.0,
    val maxHrBpm: Double = 170.0,
    val maxBadIntervalFraction: Double = 0.05,
    /** Breathing needs a long window; shorter captures report no breathing rate. */
    val minRespSeconds: Double = 30.0,
    val minRespSnrDb: Double = 3.0,
)

class FaceRppgResult(
    val accepted: Boolean,
    val hrBpm: Double?,
    val respPerMin: Double?,
    val snrDb: Double,
    /** 0..1; capped well below the fingertip's weight because a face is the weaker sensor. */
    val confidence: Double,
    val seconds: Double,
    val reasons: List<FaceReason>,
) {
    val prompt: String? get() = reasons.minByOrNull { it.ordinal }?.prompt
}

/**
 * Face pulse and breathing from colour traces (P1). Uses POS (plane-orthogonal-to-skin, Wang et al. 2017): normalise each
 * colour by its window mean, project onto the plane orthogonal to the skin-tone direction, balance the two projections
 * and overlap-add. The result is band-passed and its spectrum read. OPTIONAL: it never replaces the fingertip pulse and
 * can only add information, never reduce a level.
 */
object FaceRppg {
    const val FS = 30.0
    const val POS_WINDOW_SEC = 1.6

    /** POS on uniformly sampled traces. Returns the pulse-like signal, same length as the input. */
    fun pos(r: DoubleArray, g: DoubleArray, b: DoubleArray, fs: Double = FS, windowSec: Double = POS_WINDOW_SEC): DoubleArray {
        val n = r.size
        val l = (windowSec * fs).roundToInt().coerceAtLeast(8)
        val h = DoubleArray(n)
        if (n < l) return h
        for (t in l - 1 until n) {
            val s = t - l + 1
            var mr = 0.0; var mg = 0.0; var mb = 0.0
            for (i in s..t) { mr += r[i]; mg += g[i]; mb += b[i] }
            mr /= l; mg /= l; mb /= l
            if (mr < 1e-6 || mg < 1e-6 || mb < 1e-6) continue
            val s1 = DoubleArray(l); val s2 = DoubleArray(l)
            for (k in 0 until l) {
                val rn = r[s + k] / mr; val gn = g[s + k] / mg; val bn = b[s + k] / mb
                s1[k] = gn - bn
                s2[k] = -2.0 * rn + gn + bn
            }
            val sd2 = Dsp.std(s2)
            val alpha = if (sd2 < 1e-12) 0.0 else Dsp.std(s1) / sd2
            var m = 0.0
            for (k in 0 until l) { s1[k] += alpha * s2[k]; m += s1[k] }
            m /= l
            for (k in 0 until l) h[s + k] += s1[k] - m
        }
        return h
    }

    private class Peak(val freqHz: Double, val snrDb: Double)

    /** Strongest spectral peak in [loHz, hiHz] and its signal-to-noise ratio (peak plus second harmonic against the rest of the grid). */
    private fun spectralPeak(x: DoubleArray, fs: Double, loHz: Double, hiHz: Double, gridLo: Double, gridHi: Double, halfWidth: Double, step: Double = 0.01): Peak? {
        if (x.size < fs * 4) return null
        val steps = ((gridHi - gridLo) / step).roundToInt()
        val freqs = DoubleArray(steps + 1) { gridLo + step * it }
        val p = Dsp.powerAt(x, fs, freqs)
        var best = -1; var bestP = 0.0
        for (i in freqs.indices) if (freqs[i] in loHz..hiHz && p[i] > bestP) { bestP = p[i]; best = i }
        if (best < 0 || bestP <= 0.0) return null
        val f0 = freqs[best]
        var sig = 0.0; var total = 0.0
        for (i in freqs.indices) {
            total += p[i]
            if (abs(freqs[i] - f0) <= halfWidth || abs(freqs[i] - 2 * f0) <= halfWidth) sig += p[i]
        }
        val noise = (total - sig).coerceAtLeast(1e-30)
        return Peak(f0, 10.0 * log10(sig.coerceAtLeast(1e-30) / noise))
    }

    private fun fail(seconds: Double, vararg r: FaceReason) = FaceRppgResult(false, null, null, Double.NEGATIVE_INFINITY, 0.0, seconds, r.toList())

    fun analyse(frames: List<FaceFrame>, th: FaceThresholds = FaceThresholds()): FaceRppgResult {
        if (frames.size < 8) return fail(0.0, FaceReason.TOO_SHORT)
        val seconds = (frames.last().tNanos - frames.first().tNanos) / 1e9
        if (seconds < th.minSeconds) return fail(seconds, FaceReason.TOO_SHORT)
        val faceFrac = frames.count { it.faceFound }.toDouble() / frames.size
        if (faceFrac < th.minFaceFraction) return fail(seconds, FaceReason.NO_FACE)

        val used = frames.filter { it.faceFound }
        // frame cadence: a camera that drops or stutters breaks the pulse timing
        val dts = DoubleArray(used.size - 1) { (used[it + 1].tNanos - used[it].tNanos) / 1e9 }
        val medDt = Dsp.median(dts)
        if (medDt <= 0.0) return fail(seconds, FaceReason.UNEVEN_FRAMES)
        val badFrac = dts.count { it > 1.5 * medDt || it < 0.5 * medDt }.toDouble() / dts.size
        if (badFrac > th.maxBadIntervalFraction) return fail(seconds, FaceReason.UNEVEN_FRAMES)

        val mr = used.sumOf { it.r } / used.size
        val mg = used.sumOf { it.g } / used.size
        val mb = used.sumOf { it.b } / used.size
        val reasons = ArrayList<FaceReason>()
        if (minOf(mr, mg, mb) < th.minChannelMean) reasons += FaceReason.TOO_DARK
        if (maxOf(mr, mg, mb) > th.maxChannelMean) reasons += FaceReason.TOO_BRIGHT
        if (Dsp.median(DoubleArray(used.size) { used[it].motion }) > th.maxMedianMotion) reasons += FaceReason.MOVING
        if (reasons.isNotEmpty()) return FaceRppgResult(false, null, null, Double.NEGATIVE_INFINITY, 0.0, seconds, reasons)

        val t = DoubleArray(used.size) { (used[it].tNanos - used[0].tNanos) / 1e9 }
        val (_, r) = Dsp.resampleUniform(t, DoubleArray(used.size) { used[it].r }, FS)
        val (_, g) = Dsp.resampleUniform(t, DoubleArray(used.size) { used[it].g }, FS)
        val (_, b) = Dsp.resampleUniform(t, DoubleArray(used.size) { used[it].b }, FS)

        val pulse = Dsp.bandpass(pos(r, g, b), FS, 0.7, 3.0)
        // drop the first and last window: POS has not filled them yet
        val trim = (POS_WINDOW_SEC * FS).roundToInt()
        val core = if (pulse.size > 4 * trim) pulse.copyOfRange(trim, pulse.size - trim) else pulse
        val peak = spectralPeak(core, FS, th.minHrBpm / 60.0, th.maxHrBpm / 60.0, 0.5, 4.0, 0.12)
        if (peak == null || peak.snrDb < th.minSnrDb) {
            return FaceRppgResult(false, null, null, peak?.snrDb ?: Double.NEGATIVE_INFINITY, 0.0, seconds, listOf(FaceReason.NO_PULSE))
        }
        val hr = peak.freqHz * 60.0

        // breathing: slow wobble of the green channel, only on a long window
        val resp = if (seconds >= th.minRespSeconds) {
            val slow = Dsp.bandpass(g, FS, 0.1, 0.5)
            val rcore = if (slow.size > 4 * trim) slow.copyOfRange(trim, slow.size - trim) else slow
            val rp = spectralPeak(rcore, FS, 0.1, 0.5, 0.05, 0.7, 0.03, step = 0.005)
            if (rp != null && rp.snrDb >= th.minRespSnrDb) rp.freqHz * 60.0 else null
        } else null

        val conf = ((peak.snrDb - th.minSnrDb) / 10.0).coerceIn(0.0, 1.0) * 0.6
        return FaceRppgResult(true, hr, resp, peak.snrDb, conf, seconds, emptyList())
    }

    /**
     * A face pulse may be used at reduced weight only if it agrees with a fingertip reading taken at the same state, or if
     * no fingertip reading exists. Disagreement means distrust the face (never the fingertip).
     */
    fun agreesWithFingertip(faceHr: Double, fingertipHr: Double?, toleranceBpm: Double = 8.0): Boolean =
        fingertipHr == null || abs(faceHr - fingertipHr) <= toleranceBpm
}
