package com.thakaan.ml

import com.thakaan.ppg.Dsp
import kotlin.math.abs
import kotlin.math.sqrt

/** Raw samples from a chest recording: time in seconds and the three accelerometer axes (m/s^2); gyro magnitude (rad/s) optional. */
class ScgRecording(val t: DoubleArray, val ax: DoubleArray, val ay: DoubleArray, val az: DoubleArray, val gyroMag: DoubleArray? = null)

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
) {
    val accepted: Boolean get() = classicalBpm != null && reason == null
}

/**
 * Chest seismocardiography for ticket #30: the phone against the chest, worker seated and still. The beat shows as a small
 * sharp vibration (aortic opening) a few hertz to a few tens of hertz wide. Chain: resample to 100 Hz, take the axis with
 * the most beat-band energy, band-pass 4 to 25 Hz, slice into 5 s windows, keep only windows where the body is still (slow
 * motion and rotation both small), read the rate in each by detecting beats, and accept only if the still windows agree.
 * A learned denoiser (trained on simulated data) can be applied to each window as a second opinion.
 * Useless while walking or lifting, so it gates hard on stillness. Display only: never reaches the risk engine.
 */
object ScgPipeline {
    const val FS = 100.0
    const val WINDOW_SEC = 5.0
    const val STEP_SEC = 2.5
    const val MAX_BODY_MOTION = 0.15   // m/s^2, 0.3 to 3 Hz band: breathing and posture sway sit below this, walking far above
    const val MAX_GYRO_RMS = 0.15      // rad/s
    const val MIN_STILL_WINDOWS = 6
    const val TOLERANCE_BPM = 4.0
    const val MIN_CONSISTENCY = 0.7

    private fun median(v: List<Double>): Double { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2]) }

    fun analyse(rec: ScgRecording, denoise: ((DoubleArray) -> DoubleArray?)? = null): ScgResult {
        if (rec.t.size < 2 * FS * WINDOW_SEC || rec.t.last() - rec.t.first() < 2 * WINDOW_SEC) return ScgResult(null, null, 0, 0, 0.0, '-', "Too short: record at least 30 seconds")
        val t = DoubleArray(rec.t.size) { rec.t[it] - rec.t[0] }
        val axes = listOf(rec.ax, rec.ay, rec.az).map { try { Dsp.resampleUniform(t, it, FS).second } catch (_: IllegalArgumentException) { return ScgResult(null, null, 0, 0, 0.0, '-', "The sensor delivered irregular data") } }
        val gyro = rec.gyroMag?.let { try { Dsp.resampleUniform(t, it, FS).second } catch (_: IllegalArgumentException) { null } }
        val n = axes[0].size
        val beatBand = axes.map { Dsp.bandpass(it, FS, 4.0, 25.0) }
        val slow = axes.map { Dsp.bandpass(it, FS, 0.3, 3.0) }
        // the axis that carries the most beat-band energy (usually the one pointing into the chest)
        val energies = beatBand.map { b -> Dsp.std(b) }
        val best = energies.indices.maxByOrNull { energies[it] } ?: 0
        val axisName = "xyz"[best]
        val wave = beatBand[best]

        val win = (WINDOW_SEC * FS).toInt(); val step = (STEP_SEC * FS).toInt()
        val classical = ArrayList<Double>(); val modelled = ArrayList<Double>()
        var windows = 0; var still = 0
        var a = 0
        while (a + win <= n) {
            windows++
            val motion = slow.maxOf { s -> Dsp.std(s.copyOfRange(a, a + win)) }
            val rot = gyro?.let { g -> sqrt(g.copyOfRange(a, a + win).sumOf { it * it } / win) } ?: 0.0
            if (motion <= MAX_BODY_MOTION && rot <= MAX_GYRO_RMS) {
                still++
                val w = ScgHeart.normalise(wave.copyOfRange(a, a + win))
                ScgHeart.beats(w).bpm?.let { classical += it }
                if (denoise != null) denoise(w)?.let { c -> ScgHeart.beats(c).bpm?.let { modelled += it } }
            }
            a += step
        }
        val model = modelled.takeIf { it.size >= MIN_STILL_WINDOWS }?.let { median(it) }
        if (still < MIN_STILL_WINDOWS) return ScgResult(null, null, windows, still, 0.0, axisName, "Not still enough: sit still with the phone against your chest")
        if (classical.size < MIN_STILL_WINDOWS) return ScgResult(null, model, windows, still, 0.0, axisName, "No clear heartbeat vibration found")
        val med = median(classical)
        val consistency = classical.count { abs(it - med) <= TOLERANCE_BPM }.toDouble() / classical.size
        if (consistency < MIN_CONSISTENCY) return ScgResult(null, model, windows, still, consistency, axisName, "The rate kept changing: not trusted")
        return ScgResult(med, model, windows, still, consistency, axisName, null)
    }
}
