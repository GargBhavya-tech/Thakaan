package com.thakaan.ppg

import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/** Generates finger-PPG-like camera frames with known truth, so the DSP can be tested without a phone. */
object SyntheticPpg {
    class Spec(
        val hrBpm: Double = 75.0,
        val endHrBpm: Double = hrBpm,
        val durationSec: Double = 30.0,
        val fps: Double = 30.0,
        val dc: Double = 180.0,
        val piPercent: Double = 1.5,
        /** Noise standard deviation as a fraction of the pulse amplitude. */
        val noiseFrac: Double = 0.03,
        val hrvSdMs: Double = 40.0,
        val respBpm: Double = 15.0,
        val timingJitterMs: Double = 2.0,
        val seed: Long = 1,
        /** Motion artefacts: (startSec, durationSec, size as a multiple of the pulse amplitude). */
        val motion: List<Triple<Double, Double, Double>> = emptyList(),
        /** Drop 4 consecutive frames at these times (seconds). */
        val dropAt: List<Double> = emptyList(),
        val startNanos: Long = 1_000_000_000L,
    )

    class Result(val frames: List<PpgFrame>, val beatTimesSec: List<Double>, val ibiMs: List<Double>)

    private fun pulse(tau: Double): Double {
        if (tau < 0 || tau > 1.4) return 0.0
        val systolic = exp(-((tau - 0.15) * (tau - 0.15)) / (2 * 0.06 * 0.06))
        val dicrotic = 0.35 * exp(-((tau - 0.38) * (tau - 0.38)) / (2 * 0.07 * 0.07))
        return systolic + dicrotic
    }

    fun generate(s: Spec): Result {
        val rng = Random(s.seed)
        val beats = ArrayList<Double>()
        val ibis = ArrayList<Double>()
        var t = 0.0
        var hrvState = 0.0
        while (t < s.durationSec + 2) {
            val frac = (t / s.durationSec).coerceIn(0.0, 1.0)
            val hr = s.hrBpm + (s.endHrBpm - s.hrBpm) * frac
            // variability shrinks as the rate rises: hrvSdMs is quoted at 75 bpm (800 ms intervals)
            val sd = s.hrvSdMs * (60_000.0 / hr) / 800.0
            hrvState = 0.7 * hrvState + rng.nextGaussian() * sd * 0.714 // stationary SD ~ sd
            val ibi = 60_000.0 / hr + hrvState
            beats += t
            ibis += ibi
            t += ibi / 1000.0
        }
        val amp = s.dc * s.piPercent / 100.0
        val frames = ArrayList<PpgFrame>()
        var frameIdx = 0
        val nFrames = (s.durationSec * s.fps).toInt()
        val motionPhases = DoubleArray(3) { rng.nextDouble() * 2 * PI }
        for (n in 0 until nFrames) {
            val tNom = n / s.fps
            if (s.dropAt.any { tNom >= it && tNom < it + 4 / s.fps }) continue
            val tt = tNom + rng.nextGaussian() * s.timingJitterMs / 1000.0
            var p = 0.0
            for (b in beats) {
                val tau = tt - b
                if (tau in 0.0..1.4) p += pulse(tau)
            }
            // breathing modulates the pulse amplitude (the envelope that respiration is read from)
            var v = s.dc - amp * p * (1.0 + 0.25 * sin(2 * PI * s.respBpm / 60.0 * tt))
            v += amp * 0.4 * sin(2 * PI * 0.07 * tt + 1.0)                 // slow baseline wander
            v += amp * 0.1 * sin(2 * PI * s.respBpm / 60.0 * tt)           // breathing
            v += rng.nextGaussian() * s.noiseFrac * amp
            for ((start, dur, mult) in s.motion) {
                if (tt in start..(start + dur)) {
                    v += mult * amp * (sin(2 * PI * 0.9 * tt + motionPhases[0]) + 0.8 * sin(2 * PI * 1.9 * tt + motionPhases[1]) + 0.6 * sin(2 * PI * 3.3 * tt + motionPhases[2]))
                }
            }
            frames += PpgFrame(s.startNanos + (tt * 1e9).toLong(), v, v * 0.6)
            frameIdx++
        }
        return Result(frames, beats, ibis)
    }
}
