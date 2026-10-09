package com.thakaan.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DspTest {
    @Test fun splineReproducesASineSampledUnevenly() {
        val rng = Random(3)
        val t = DoubleArray(300)
        for (i in 1 until t.size) t[i] = t[i - 1] + 1.0 / 30 + rng.nextGaussian() * 0.003
        val y = DoubleArray(t.size) { sin(2 * PI * 1.2 * t[it]) }
        val (tg, yg) = Dsp.resampleUniform(t, y, 100.0)
        var worst = 0.0
        for (i in 20 until tg.size - 20) worst = maxOf(worst, abs(yg[i] - sin(2 * PI * 1.2 * tg[i])))
        assertTrue("spline error $worst", worst < 0.01)
    }

    @Test fun bandpassKeepsThePulseBandAndKillsTheRest() {
        val fs = 100.0
        fun gain(f: Double): Double {
            val x = DoubleArray(2000) { sin(2 * PI * f * it / fs) }
            val y = Dsp.bandpass(x, fs)
            val mid = y.copyOfRange(500, 1500)
            return sqrt(mid.sumOf { it * it } / mid.size) / (1 / sqrt(2.0))
        }
        assertTrue("1.5 Hz passes: ${gain(1.5)}", gain(1.5) in 0.9..1.1)
        assertTrue("0.05 Hz is cut: ${gain(0.05)}", gain(0.05) < 0.1)
        assertTrue("12 Hz is cut: ${gain(12.0)}", gain(12.0) < 0.1)
    }

    @Test fun bandpassIsZeroPhase() {
        val fs = 100.0
        val x = DoubleArray(2000) { sin(2 * PI * 1.5 * it / fs) }
        val y = Dsp.bandpass(x, fs)
        // cross-correlation peak at lag 0
        var bestLag = 0
        var best = -1e9
        for (lag in -10..10) {
            var acc = 0.0
            for (i in 500..1500) acc += x[i] * y[i + lag]
            if (acc > best) { best = acc; bestLag = lag }
        }
        assertEquals(0, bestLag)
    }

    @Test fun waveletReconstructsPerfectly() {
        val rng = Random(5)
        val x = DoubleArray(256) { rng.nextGaussian() }
        val (a, d) = Wavelet.forward(x)
        val back = Wavelet.inverse(a, d)
        for (i in x.indices) assertEquals(x[i], back[i], 1e-12)
    }

    @Test fun waveletDenoiseLowersNoiseAndKeepsTheSignal() {
        val rng = Random(9)
        val n = 3000
        val clean = DoubleArray(n) { sin(2 * PI * 1.3 * it / 100.0) }
        val noisy = DoubleArray(n) { clean[it] + rng.nextGaussian() * 0.25 }
        val den = Wavelet.denoise(noisy)
        fun rmse(a: DoubleArray) = sqrt(a.indices.sumOf { (a[it] - clean[it]) * (a[it] - clean[it]) } / n)
        assertTrue("noise ${rmse(noisy)} -> ${rmse(den)}", rmse(den) < rmse(noisy) * 0.8)
    }
}
