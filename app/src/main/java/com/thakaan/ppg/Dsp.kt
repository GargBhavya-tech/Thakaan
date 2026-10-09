package com.thakaan.ppg

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Small numeric toolbox used by the PPG chain. Pure Kotlin so it runs the same on the laptop and the phone. */
object Dsp {
    fun mean(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else x.sum() / x.size

    fun std(x: DoubleArray): Double {
        if (x.size < 2) return 0.0
        val m = mean(x)
        return sqrt(x.sumOf { (it - m) * (it - m) } / (x.size - 1))
    }

    fun median(x: DoubleArray): Double {
        if (x.isEmpty()) return Double.NaN
        val s = x.sortedArray()
        val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2
    }

    /** Linear-interpolated percentile, p in 0..100. */
    fun percentile(x: DoubleArray, p: Double): Double {
        if (x.isEmpty()) return Double.NaN
        val s = x.sortedArray()
        val pos = p / 100.0 * (s.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, s.size - 1)
        return s[lo] + (s[hi] - s[lo]) * (pos - lo)
    }

    /** Robust spread of a signal: 95th minus 5th percentile (ignores a few spikes, unlike max - min). */
    fun robustRange(x: DoubleArray): Double = percentile(x, 95.0) - percentile(x, 5.0)

    // ---------------------------------------------------------------- cubic spline resampling

    /**
     * Natural cubic spline through (t[i], y[i]) evaluated on a uniform grid at [fs] Hz from t[0] to t[last].
     * Camera frames arrive with jitter and the odd drop; HRV needs an evenly sampled signal.
     */
    fun resampleUniform(t: DoubleArray, y: DoubleArray, fs: Double): Pair<DoubleArray, DoubleArray> {
        require(t.size == y.size && t.size >= 4) { "need at least 4 points" }
        val n = t.size
        val h = DoubleArray(n - 1) { t[it + 1] - t[it] }
        require(h.all { it > 0 }) { "timestamps must be strictly increasing" }

        // Solve the tridiagonal system for second derivatives m (natural ends: m0 = m_{n-1} = 0).
        val m = DoubleArray(n)
        if (n > 2) {
            val a = DoubleArray(n - 2) // sub
            val b = DoubleArray(n - 2) // diag
            val c = DoubleArray(n - 2) // super
            val d = DoubleArray(n - 2)
            for (i in 1 until n - 1) {
                val k = i - 1
                a[k] = h[i - 1]
                b[k] = 2 * (h[i - 1] + h[i])
                c[k] = h[i]
                d[k] = 6 * ((y[i + 1] - y[i]) / h[i] - (y[i] - y[i - 1]) / h[i - 1])
            }
            for (k in 1 until n - 2) { // Thomas algorithm
                val w = a[k] / b[k - 1]
                b[k] -= w * c[k - 1]
                d[k] -= w * d[k - 1]
            }
            val sol = DoubleArray(n - 2)
            sol[n - 3] = d[n - 3] / b[n - 3]
            for (k in n - 4 downTo 0) sol[k] = (d[k] - c[k] * sol[k + 1]) / b[k]
            for (k in 0 until n - 2) m[k + 1] = sol[k]
        }

        val duration = t[n - 1] - t[0]
        val count = (duration * fs).toInt() + 1
        val tg = DoubleArray(count) { t[0] + it / fs }
        val yg = DoubleArray(count)
        var seg = 0
        for (j in 0 until count) {
            val x = tg[j]
            while (seg < n - 2 && x > t[seg + 1]) seg++
            val hi = h[seg]
            val l = t[seg + 1] - x
            val r = x - t[seg]
            yg[j] = m[seg] * l * l * l / (6 * hi) + m[seg + 1] * r * r * r / (6 * hi) +
                (y[seg] / hi - m[seg] * hi / 6) * l + (y[seg + 1] / hi - m[seg + 1] * hi / 6) * r
        }
        return tg to yg
    }

    // ---------------------------------------------------------------- Butterworth biquads and zero-phase filtering

    class Biquad(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double) {
        fun apply(x: DoubleArray): DoubleArray {
            val y = DoubleArray(x.size)
            var x1 = 0.0; var x2 = 0.0; var y1 = 0.0; var y2 = 0.0
            // start from the first sample so the DC step does not ring
            if (x.isNotEmpty()) { x1 = x[0]; x2 = x[0] }
            for (i in x.indices) {
                val out = b0 * x[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                y[i] = out
                x2 = x1; x1 = x[i]; y2 = y1; y1 = out
            }
            return y
        }
    }

    private fun butterQ(): Double = 1.0 / sqrt(2.0)

    fun lowpass(fc: Double, fs: Double): Biquad {
        val k = tan(PI * fc / fs)
        val norm = 1.0 / (1.0 + k / butterQ() + k * k)
        val b0 = k * k * norm
        return Biquad(b0, 2 * b0, b0, 2 * (k * k - 1) * norm, (1.0 - k / butterQ() + k * k) * norm)
    }

    fun highpass(fc: Double, fs: Double): Biquad {
        val k = tan(PI * fc / fs)
        val norm = 1.0 / (1.0 + k / butterQ() + k * k)
        return Biquad(norm, -2 * norm, norm, 2 * (k * k - 1) * norm, (1.0 - k / butterQ() + k * k) * norm)
    }

    /** Odd reflection about the end points: removes the start/end transient of forward-backward filtering. */
    private fun oddExtend(x: DoubleArray, pad: Int): DoubleArray {
        val n = x.size
        val p = minOf(pad, n - 1)
        val out = DoubleArray(n + 2 * p)
        for (i in 0 until p) {
            out[i] = 2 * x[0] - x[p - i]
            out[n + p + i] = 2 * x[n - 1] - x[n - 2 - i]
        }
        System.arraycopy(x, 0, out, p, n)
        return out
    }

    /** Zero-phase filtering: run the cascade forward, reverse, run again, reverse. Magnitude is squared, phase cancels. */
    fun filtfilt(x: DoubleArray, vararg sections: Biquad, padSamples: Int = 300): DoubleArray {
        if (x.size < 8) return x.copyOf()
        val pad = minOf(padSamples, x.size - 1)
        var y = oddExtend(x, pad)
        for (s in sections) y = s.apply(y)
        y.reverse()
        for (s in sections) y = s.apply(y)
        y.reverse()
        return y.copyOfRange(pad, pad + x.size)
    }

    /** Band-pass 0.5 to 4.0 Hz by default: 2nd-order Butterworth high-pass and low-pass, each applied forward and backward. */
    fun bandpass(x: DoubleArray, fs: Double, lowHz: Double = 0.5, highHz: Double = 4.0): DoubleArray =
        filtfilt(x, highpass(lowHz, fs), lowpass(highHz, fs), padSamples = (2 * fs).toInt())

    // ---------------------------------------------------------------- spectrum

    /** Hann-windowed power at each frequency in [freqs] (Hz) by direct DFT. Sizes here are small, so no FFT is needed. */
    fun powerAt(x: DoubleArray, fs: Double, freqs: DoubleArray): DoubleArray {
        val n = x.size
        val w = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1).coerceAtLeast(1)) }
        val mean = mean(x)
        val xs = DoubleArray(n) { (x[it] - mean) * w[it] }
        return DoubleArray(freqs.size) { k ->
            val omega = 2 * PI * freqs[k] / fs
            var re = 0.0
            var im = 0.0
            // recurrence rotation instead of sin/cos per sample
            val cw = cos(omega); val sw = sin(omega)
            var c = 1.0; var s = 0.0
            for (i in 0 until n) {
                re += xs[i] * c
                im -= xs[i] * s
                val nc = c * cw - s * sw
                s = s * cw + c * sw
                c = nc
            }
            (re * re + im * im) / n
        }
    }

    fun absMax(x: DoubleArray): Double = x.maxOfOrNull { abs(it) } ?: 0.0
}
