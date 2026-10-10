package com.thakaan.ml

import com.thakaan.ppg.Dsp
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Raw samples from a chest recording: time in seconds and the three accelerometer axes (m/s^2); gyro magnitude (rad/s)
 * optional. [gyroAxes] (x, y, z in rad/s) lets the motion canceller use the rotation itself, not only its size.
 */
class ScgRecording(
    val t: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray,
    val gyroMag: DoubleArray? = null,
    val gyroAxes: List<DoubleArray>? = null,
)

class ScgResult(
    /** Heart rate from the classical chain (band-pass, beat finder) over still windows, or null. */
    val classicalBpm: Double?,
    /** Same with the learned denoiser applied first; null when no model was given or it found nothing. */
    val modelBpm: Double?,
    val windows: Int,
    val stillWindows: Int,
    /** Share of the still windows whose rate sits within a few beats of the median. */
    val consistency: Double,
    val axis: Char,
    val reason: String?,
    /** 0..1: how steady the body was (1 = perfectly still). Mean over all windows; a quality input, never a level input. */
    val motionQuality: Double = 0.0,
    /** Breaths a minute from the slow chest sway, when the recording was still enough to read it. */
    val breathsPerMin: Double? = null,
) {
    val accepted: Boolean get() = classicalBpm != null && reason == null
}

/**
 * Chest seismocardiography for ticket #30: the phone against the chest, worker seated and still. The beat shows as a small
 * sharp vibration (aortic opening) a few hertz to a few tens of hertz wide.
 *
 * Chain: resample to 100 Hz, band-pass 4 to 25 Hz, slice into 5 s windows. Each window is checked for stillness (slow
 * motion, rotation, a tilt of the phone against the previous window = posture change, a gap in the sensor data), and the
 * beat signal is the first principal component of the three axes in that window, so a phone sitting tilted in the pocket
 * works. Each rate is read by two independent methods (beat spacing and autocorrelation) and a window counts only if they
 * agree, which kills half- and double-rate readings. Accepted only if enough still windows agree with each other.
 * Useless while walking or lifting, so it gates hard on stillness.
 */
object ScgPipeline {
    const val FS = 100.0
    const val WINDOW_SEC = 5.0
    const val STEP_SEC = 2.5
    const val MAX_BODY_MOTION = 0.15   // m/s^2, 0.3 to 3 Hz band: breathing and posture sway sit below this, walking far above
    const val MAX_GYRO_RMS = 0.15      // rad/s
    const val MAX_TILT_DEG = 4.0       // gravity direction moving more than this between windows = posture change
    const val POSTURE_SETTLE_SEC = 30.0
    const val MAX_GAP_SEC = 0.1        // a hole in the sensor stream longer than this spoils every window it touches
    const val METHOD_AGREEMENT = 0.08  // beat spacing and autocorrelation must agree within 8%
    const val MIN_STILL_WINDOWS = 6
    const val TOLERANCE_BPM = 4.0
    const val MIN_CONSISTENCY = 0.7

    private fun median(v: List<Double>): Double { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2]) }

    /**
     * [cancelMotion] runs an adaptive filter that subtracts what the rotation sensor explains from the beat band before
     * detection (ticket #30 "gyro-referenced adaptive cancelling"). Off by default until real recordings show it helps.
     */
    fun analyse(rec: ScgRecording, cancelMotion: Boolean = false, denoise: ((DoubleArray) -> DoubleArray?)? = null): ScgResult {
        if (rec.t.size < 2 * FS * WINDOW_SEC || rec.t.last() - rec.t.first() < 2 * WINDOW_SEC) return ScgResult(null, null, 0, 0, 0.0, '-', "Too short: record at least 30 seconds")
        val t = DoubleArray(rec.t.size) { rec.t[it] - rec.t[0] }
        val fail = ScgResult(null, null, 0, 0, 0.0, '-', "The sensor delivered irregular data")
        fun rs(v: DoubleArray): DoubleArray? = try { Dsp.resampleUniform(t, v, FS).second } catch (_: IllegalArgumentException) { null }
        val axes = listOf(rec.ax, rec.ay, rec.az).map { rs(it) ?: return fail }
        val gyroAxes = rec.gyroAxes?.map { rs(it) ?: return fail }
        val gyro = rec.gyroMag?.let { rs(it) }
        val n = axes[0].size
        var beatBand = axes.map { Dsp.bandpass(it, FS, 4.0, 25.0) }
        if (cancelMotion) {
            val refs = gyroAxes?.map { Dsp.bandpass(it, FS, 0.3, 25.0) } ?: gyro?.let { listOf(Dsp.bandpass(it, FS, 0.3, 25.0)) }
            if (refs != null) beatBand = beatBand.map { Nlms.cancel(it, refs) }
        }
        val slow = axes.map { Dsp.bandpass(it, FS, 0.3, 3.0) }
        val sway = axes.map { Dsp.bandpass(it, FS, 0.1, 0.7) }

        // spans of the original recording with a hole in the data; windows touching one are dropped
        val gaps = ArrayList<Pair<Double, Double>>()
        for (i in 1 until t.size) if (t[i] - t[i - 1] > MAX_GAP_SEC) gaps += t[i - 1] to t[i]

        val win = (WINDOW_SEC * FS).toInt(); val step = (STEP_SEC * FS).toInt()
        val classical = ArrayList<Double>(); val modelled = ArrayList<Double>()
        val axisVotes = IntArray(3)
        var windows = 0; var still = 0
        var qualitySum = 0.0
        var prevGravity: DoubleArray? = null
        var settleUntil = -1.0
        var a = 0
        while (a + win <= n) {
            windows++
            val t0 = a / FS; val t1 = (a + win) / FS
            val g = DoubleArray(3) { k -> axes[k].let { x -> var s = 0.0; for (i in a until a + win) s += x[i]; s / win } }
            val tilt = prevGravity?.let { angleDeg(it, g) } ?: 0.0
            prevGravity = g
            if (tilt > MAX_TILT_DEG) settleUntil = t1 + POSTURE_SETTLE_SEC
            val motion = slow.maxOf { s -> Dsp.std(s.copyOfRange(a, a + win)) }
            val rot = gyro?.let { gm -> sqrt(gm.copyOfRange(a, a + win).sumOf { it * it } / win) } ?: 0.0
            qualitySum += (1.0 - maxOf(motion / (2 * MAX_BODY_MOTION), rot / (2 * MAX_GYRO_RMS))).coerceIn(0.0, 1.0)
            val hole = gaps.any { it.second > t0 && it.first < t1 }
            val steady = motion <= MAX_BODY_MOTION && rot <= MAX_GYRO_RMS && !hole && t0 >= settleUntil
            if (steady) {
                still++
                val (wave, axis) = principal(beatBand, a, win)
                axisVotes[axis]++
                val w = ScgHeart.normalise(wave)
                val peakBpm = ScgHeart.beats(w).bpm
                val acBpm = autocorrBpm(w)
                // two independent readings must agree, or the window says nothing
                if (peakBpm != null && acBpm != null && abs(peakBpm - acBpm) <= METHOD_AGREEMENT * acBpm) {
                    classical += peakBpm
                    if (denoise != null) denoise(w)?.let { c -> ScgHeart.beats(c).bpm?.let { modelled += it } }
                }
            }
            a += step
        }
        val axisName = "xyz"[axisVotes.indices.maxByOrNull { axisVotes[it] } ?: 0]
        val quality = if (windows == 0) 0.0 else qualitySum / windows
        val model = modelled.takeIf { it.size >= MIN_STILL_WINDOWS }?.let { median(it) }
        // breathing is too slow for one 5 s window: read it once over the whole recording, only if it was still enough
        val resp = if (still >= MIN_STILL_WINDOWS) dominantFrequencyBpm(sway.maxByOrNull { Dsp.std(it) }!!, 0.1, 0.7) else null
        fun res(c: Double?, m: Double?, cons: Double, why: String?) = ScgResult(c, m, windows, still, cons, axisName, why, quality, resp)
        if (still < MIN_STILL_WINDOWS) return res(null, null, 0.0, "Not still enough: sit still with the phone against your chest")
        if (classical.size < MIN_STILL_WINDOWS) return res(null, model, 0.0, "No clear heartbeat vibration found")
        val med = median(classical)
        val consistency = classical.count { abs(it - med) <= TOLERANCE_BPM }.toDouble() / classical.size
        if (consistency < MIN_CONSISTENCY) return res(null, model, consistency, "The rate kept changing: not trusted")
        return res(med, model, consistency, null)
    }

    private fun angleDeg(u: DoubleArray, v: DoubleArray): Double {
        val nu = sqrt(u.sumOf { it * it }); val nv = sqrt(v.sumOf { it * it })
        if (nu < 1e-6 || nv < 1e-6) return 0.0
        val c = ((0 until 3).sumOf { u[it] * v[it] } / (nu * nv)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(c))
    }

    /** First principal component of the three beat-band axes in one window, and the axis it leans on most. */
    internal fun principal(b: List<DoubleArray>, a: Int, win: Int): Pair<DoubleArray, Int> {
        val m = DoubleArray(3) { k -> var s = 0.0; for (i in a until a + win) s += b[k][i]; s / win }
        val c = Array(3) { DoubleArray(3) }
        for (i in a until a + win) for (p in 0 until 3) for (q in p until 3) c[p][q] += (b[p][i] - m[p]) * (b[q][i] - m[q])
        for (p in 0 until 3) for (q in 0 until p) c[p][q] = c[q][p]
        var v = doubleArrayOf(1.0, 1.0, 1.0)
        repeat(40) {
            val nv = DoubleArray(3) { p -> c[p][0] * v[0] + c[p][1] * v[1] + c[p][2] * v[2] }
            val norm = sqrt(nv.sumOf { it * it })
            if (norm < 1e-12) return DoubleArray(win) { b[0][a + it] } to 0
            v = DoubleArray(3) { nv[it] / norm }
        }
        val wave = DoubleArray(win) { i -> (0 until 3).sumOf { v[it] * (b[it][a + i] - m[it]) } }
        return wave to (0 until 3).maxByOrNull { abs(v[it]) }!!
    }

    /** Heart rate from the autocorrelation of the smoothed envelope: the lag with the strongest repeat between 0.33 and 1.5 s. */
    internal fun autocorrBpm(x: DoubleArray): Double? {
        val env = envelope(x)
        val m = env.average()
        val e = DoubleArray(env.size) { env[it] - m }
        val e0 = e.sumOf { it * it }
        if (e0 < 1e-9) return null
        val lo = (0.33 * FS).toInt(); val hi = minOf((1.5 * FS).toInt(), e.size / 2)
        var best = -1; var bestR = 0.0
        for (lag in lo..hi) {
            var s = 0.0
            for (i in 0 until e.size - lag) s += e[i] * e[i + lag]
            val r = s / e0
            if (r > bestR + 1e-9) { bestR = r; best = lag }
        }
        if (best < 0 || bestR < 0.2) return null
        return 60.0 * FS / best
    }

    private fun envelope(x: DoubleArray): DoubleArray {
        val w = 4
        return DoubleArray(x.size) { i ->
            var s = 0.0; var c = 0
            for (j in (i - w).coerceAtLeast(0)..(i + w).coerceAtMost(x.size - 1)) { s += abs(x[j]); c++ }
            s / c
        }
    }

    /** Frequency (per minute) of the strongest sine between [loHz] and [hiHz], by a plain DFT scan; null if flat. */
    internal fun dominantFrequencyBpm(x: DoubleArray, loHz: Double, hiHz: Double): Double? {
        val mean = x.average()
        var best = 0.0; var bestP = 0.0; var total = 0.0
        var f = loHz
        while (f <= hiHz) {
            var re = 0.0; var im = 0.0
            for (i in x.indices) { val ph = 2 * Math.PI * f * i / FS; re += (x[i] - mean) * Math.cos(ph); im += (x[i] - mean) * Math.sin(ph) }
            val p = re * re + im * im
            total += p
            if (p > bestP) { bestP = p; best = f }
            f += 0.01
        }
        return if (total <= 0.0 || bestP / total < 0.15) null else best * 60.0
    }
}

/** Normalised-LMS canceller: removes from [primary] whatever a linear filter of the [refs] can explain. */
internal object Nlms {
    fun cancel(primary: DoubleArray, refs: List<DoubleArray>, taps: Int = 8, mu: Double = 0.5): DoubleArray {
        val w = Array(refs.size) { DoubleArray(taps) }
        val out = DoubleArray(primary.size)
        for (i in primary.indices) {
            var y = 0.0; var power = 1e-6
            for (r in refs.indices) for (k in 0 until taps) { val s = if (i - k >= 0) refs[r][i - k] else 0.0; y += w[r][k] * s; power += s * s }
            val err = primary[i] - y
            out[i] = err
            val g = mu * err / power
            for (r in refs.indices) for (k in 0 until taps) { val s = if (i - k >= 0) refs[r][i - k] else 0.0; w[r][k] += g * s }
        }
        return out
    }
}
