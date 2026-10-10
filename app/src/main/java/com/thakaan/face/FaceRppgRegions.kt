package com.thakaan.face

import com.thakaan.ppg.Dsp
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt

/** Settings for the region method. Starting values from two real recordings on the iQOO 15; they need a reference heart rate. */
data class RegionThresholds(
    val minSeconds: Double = 24.0,
    val windowSec: Double = 16.0,
    val stepSec: Double = 2.0,
    /** A real pulse drifts slowly and noise jumps: neighbouring windows must differ by at most this many beats a minute... */
    val toleranceBpm: Double = 3.0,
    /** ...for at least this share of neighbouring pairs, for each of the two colour methods... */
    val minSmooth: Double = 0.8,
    /** ...and the middle half of the window rates (25th to 75th percentile) must span no more than this, so a pulse that changed for good is not averaged. */
    val maxSpreadBpm: Double = 6.0,
    /** The two colour methods must land this close to each other. */
    val methodAgreeBpm: Double = 4.0,
    val minWindows: Int = 4,
)

/**
 * The face pulse from each skin region on its own. Real recordings showed that one pooled trace read in short windows gives
 * scattered estimates, because lighting drift and camera noise are larger than the pulse itself. So POS and CHROM (two
 * different colour projections) are run on each region (forehead, left and right cheek), each result is normalised and the
 * regions are summed (the noise differs between regions, the pulse does not), the rate is read in overlapping windows, and the
 * pulse is accepted only if the windows agree with their own median for BOTH methods and the two methods agree with each
 * other. Display only: it never reaches the risk engine, so a wrong value cannot change a level. Returns null when the frames
 * carry no per-region means, so the caller falls back to [FaceRppg.analyse].
 */
object FaceRppgRegions {
    private const val FS = FaceRppg.FS

    /** CHROM (de Haan and Jeanne 2013), the cross-check method. Same shape as [FaceRppg.pos]. */
    fun chrom(r: DoubleArray, g: DoubleArray, b: DoubleArray, fs: Double = FS, windowSec: Double = FaceRppg.POS_WINDOW_SEC): DoubleArray {
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
            val xs = DoubleArray(l); val ys = DoubleArray(l)
            for (k in 0 until l) {
                val rn = r[s + k] / mr; val gn = g[s + k] / mg; val bn = b[s + k] / mb
                xs[k] = 3 * rn - 2 * gn
                ys[k] = 1.5 * rn + gn - 1.5 * bn
            }
            val sy = Dsp.std(ys)
            val alpha = if (sy < 1e-12) 0.0 else Dsp.std(xs) / sy
            var m = 0.0
            for (k in 0 until l) { xs[k] -= alpha * ys[k]; m += xs[k] }
            m /= l
            for (k in 0 until l) h[s + k] += xs[k] - m
        }
        return h
    }

    private fun peakBpm(x: DoubleArray): Double? {
        if (x.size < FS * 4) return null
        val freqs = DoubleArray(((3.0 - 0.75) / 0.01).roundToInt() + 1) { 0.75 + 0.01 * it }
        val p = Dsp.powerAt(x, FS, freqs)
        var best = 0
        for (i in p.indices) if (p[i] > p[best]) best = i
        return if (p[best] <= 0.0) null else freqs[best] * 60.0
    }

    private fun median(v: List<Double>): Double { val s = v.sorted(); return if (s.size % 2 == 1) s[s.size / 2] else 0.5 * (s[s.size / 2 - 1] + s[s.size / 2]) }

    private fun fail(seconds: Double, vararg r: FaceReason) = FaceRppgResult(false, null, null, Double.NEGATIVE_INFINITY, 0.0, seconds, r.toList())

    fun analyse(frames: List<FaceFrame>, th: RegionThresholds = RegionThresholds(), base: FaceThresholds = FaceThresholds(), debug: ((List<Double>, List<Double>) -> Unit)? = null): FaceRppgResult? {
        val usable = frames.filter { it.faceFound && it.regions.size >= 2 }
        if (usable.size < 60 || frames.size < 8) return null
        val nReg = usable.minOf { it.regions.size }.coerceAtMost(3)
        val seconds = (frames.last().tNanos - frames.first().tNanos) / 1e9
        if (seconds < th.minSeconds) return fail(seconds, FaceReason.TOO_SHORT)
        if (usable.size.toDouble() / frames.size < base.minFaceFraction) return fail(seconds, FaceReason.NO_FACE)
        val dts = DoubleArray(usable.size - 1) { (usable[it + 1].tNanos - usable[it].tNanos) / 1e9 }
        val medDt = Dsp.median(dts)
        if (medDt <= 0.0 || dts.count { it > 1.5 * medDt || it < 0.5 * medDt }.toDouble() / dts.size > base.maxBadIntervalFraction) return fail(seconds, FaceReason.UNEVEN_FRAMES)
        val mean = DoubleArray(3) { c -> usable.sumOf { f -> f.regions.take(nReg).sumOf { it[c] } / nReg } / usable.size }
        val reasons = ArrayList<FaceReason>()
        if (mean.min() < base.minChannelMean) reasons += FaceReason.TOO_DARK
        if (mean.max() > base.maxChannelMean) reasons += FaceReason.TOO_BRIGHT
        if (Dsp.median(DoubleArray(usable.size) { usable[it].motion }) > base.maxMedianMotion) reasons += FaceReason.MOVING
        if (reasons.isNotEmpty()) return FaceRppgResult(false, null, null, Double.NEGATIVE_INFINITY, 0.0, seconds, reasons)

        val t = DoubleArray(usable.size) { (usable[it].tNanos - usable[0].tNanos) / 1e9 }
        var posSum: DoubleArray? = null
        var chromSum: DoubleArray? = null
        try {
            val trim = (FaceRppg.POS_WINDOW_SEC * FS).roundToInt()
            for (k in 0 until nReg) {
                val r = Dsp.resampleUniform(t, DoubleArray(usable.size) { usable[it].regions[k][0] }, FS).second
                val g = Dsp.resampleUniform(t, DoubleArray(usable.size) { usable[it].regions[k][1] }, FS).second
                val b = Dsp.resampleUniform(t, DoubleArray(usable.size) { usable[it].regions[k][2] }, FS).second
                fun prep(x: DoubleArray): DoubleArray {
                    val f = Dsp.bandpass(x, FS, 0.7, 3.0)
                    val core = if (f.size > 4 * trim) f.copyOfRange(trim, f.size - trim) else f
                    val sd = Dsp.std(core).coerceAtLeast(1e-12)
                    return DoubleArray(core.size) { core[it] / sd }
                }
                val p = prep(FaceRppg.pos(r, g, b)); val c = prep(chrom(r, g, b))
                val ps = posSum; val cs = chromSum
                posSum = if (ps == null) p else DoubleArray(p.size) { ps[it] + p[it] }
                chromSum = if (cs == null) c else DoubleArray(c.size) { cs[it] + c[it] }
            }
        } catch (_: IllegalArgumentException) { return fail(seconds, FaceReason.UNEVEN_FRAMES) }
        val pSum = posSum ?: return fail(seconds, FaceReason.UNEVEN_FRAMES)
        val cSum = chromSum ?: return fail(seconds, FaceReason.UNEVEN_FRAMES)

        val win = (th.windowSec * FS).roundToInt(); val step = (th.stepSec * FS).roundToInt()
        fun rates(x: DoubleArray): List<Double> {
            val out = ArrayList<Double>(); var a = 0
            while (a + win <= x.size) { peakBpm(x.copyOfRange(a, a + win))?.let { out += it }; a += step }
            return out
        }
        val pr = rates(pSum); val cr = rates(cSum)
        if (pr.size < th.minWindows || cr.size < th.minWindows) return fail(seconds, FaceReason.TOO_SHORT)
        debug?.invoke(pr, cr)
        val pm = median(pr); val cm = median(cr)
        fun smooth(v: List<Double>) = (1 until v.size).count { abs(v[it] - v[it - 1]) <= th.toleranceBpm }.toDouble() / (v.size - 1)
        fun spread(v: List<Double>): Double { val q = v.sorted(); return q[(q.size * 3) / 4] - q[q.size / 4] }
        val estimate = (pm + cm) / 2
        val inRange = pm in base.minHrBpm..base.maxHrBpm && cm in base.minHrBpm..base.maxHrBpm
        val steadiness = minOf(smooth(pr), smooth(cr))
        val accepted = inRange && steadiness >= th.minSmooth && spread(pr) <= th.maxSpreadBpm && spread(cr) <= th.maxSpreadBpm && abs(pm - cm) <= th.methodAgreeBpm
        if (!accepted) return FaceRppgResult(false, null, null, Double.NEGATIVE_INFINITY, 0.0, seconds, listOf(FaceReason.NO_PULSE), estimate.takeIf { inRange })
        return FaceRppgResult(true, estimate, null, 10.0 * log10(1.0 + 8.0 * steadiness), (0.3 + 0.3 * steadiness).coerceIn(0.0, 0.6), seconds, emptyList(), estimate)
    }

    /** One breathing estimate and how many independent traces agreed with it. */
    class Breathing(val perMin: Double?, val agreeing: Int, val traces: Int)

    private fun traceOf(frames: List<FaceFrame>, pick: (FaceFrame) -> DoubleArray?, c: Int): DoubleArray? {
        val ok = frames.filter { pick(it) != null }
        if (ok.size < 60) return null
        val t = DoubleArray(ok.size) { (ok[it].tNanos - ok[0].tNanos) / 1e9 }
        return try { Dsp.resampleUniform(t, DoubleArray(ok.size) { pick(ok[it])!![c] }, FS).second } catch (_: IllegalArgumentException) { null }
    }

    /**
     * Breathing by consensus. Breathing shows as a faint slow wobble in skin colour that lighting drift can swamp, so one
     * trace is not trusted. Every skin region and colour channel gives its own estimate (after removing the background
     * patch's drift, if present); the answer is the median, and it is reported only if most traces fall in one narrow cluster. A real
     * breathing rate shows up in many traces, drift and noise scatter. Needs 30 s or more. Display only.
     */
    fun breathing(frames: List<FaceFrame>, clusterWidthBpm: Double = 4.0, minAgreeShare: Double = 0.85, minSeconds: Double = 30.0): Breathing {
        val used = frames.filter { it.faceFound && it.regions.size >= 3 }
        if (used.size < 60 || (used.last().tNanos - used.first().tNanos) / 1e9 < minSeconds) return Breathing(null, 0, 0)
        val trim = (FaceRppg.POS_WINDOW_SEC * FS).roundToInt()
        val freqs = DoubleArray(((0.6 - 0.1) / 0.005).roundToInt() + 1) { 0.1 + 0.005 * it }
        val peaks = ArrayList<Double>()
        for (k in 0 until 3) for (c in 0 until 3) {
            val y = traceOf(used, { it.regions[k] }, c) ?: continue
            val mean = y.average(); if (mean < 1.0) continue
            var z = DoubleArray(y.size) { y[it] / mean }
            val bgRaw = traceOf(used.filter { it.background != null }.takeIf { it.size == used.size } ?: emptyList(), { it.background }, c)
            if (bgRaw != null && bgRaw.size == z.size) {
                val bm = bgRaw.average()
                if (bm > 1.0) {
                    val b = DoubleArray(bgRaw.size) { bgRaw[it] / bm }
                    val fb = Dsp.bandpass(b, FS, 0.1, 0.5); val fz = Dsp.bandpass(z, FS, 0.1, 0.5)
                    var num = 0.0; var den = 1e-12
                    for (i in fb.indices) { num += fb[i] * fz[i]; den += fb[i] * fb[i] }
                    val beta = num / den
                    z = DoubleArray(z.size) { z[it] - beta * b[it] }
                }
            }
            val f = Dsp.bandpass(z, FS, 0.1, 0.5)
            val core = if (f.size > 4 * trim) f.copyOfRange(trim, f.size - trim) else f
            val p = Dsp.powerAt(core, FS, freqs)
            var best = 0
            for (i in p.indices) if (p[i] > p[best]) best = i
            if (p[best] > 0.0) peaks += freqs[best] * 60.0
        }
        if (peaks.size < 6) return Breathing(null, 0, peaks.size)
        // the densest cluster of estimates: a real breathing rate puts most traces in one narrow range, drift and noise scatter them
        val sorted = peaks.sorted()
        var bestStart = 0; var bestCount = 0
        for (i in sorted.indices) {
            val c = sorted.count { it >= sorted[i] && it <= sorted[i] + clusterWidthBpm }
            if (c > bestCount) { bestCount = c; bestStart = i }
        }
        val cluster = sorted.filter { it >= sorted[bestStart] && it <= sorted[bestStart] + clusterWidthBpm }
        val med = median(cluster)
        val agree = cluster.size
        val ok = agree.toDouble() / peaks.size >= minAgreeShare && med in 7.0..28.0
        return Breathing(if (ok) med else null, agree, peaks.size)
    }

    /**
     * The pulse wave for beat timing: POS from each region, summed, band-passed in a narrow band around the measured pulse
     * [centreHz] (a wide band lets noise peaks look like beats). The narrow band also makes beats look more regular than
     * they are, so beat-to-beat variability read from it is a lower, rough figure. 30 Hz uniform; the first and last
     * second of POS warm-up are trimmed.
     */
    fun pulseWave(frames: List<FaceFrame>, centreHz: Double, halfBandHz: Double = 0.3): DoubleArray? {
        val used = frames.filter { it.faceFound && it.regions.size >= 2 }
        if (used.size < 90) return null
        val nReg = used.minOf { it.regions.size }.coerceAtMost(3)
        val trim = (FaceRppg.POS_WINDOW_SEC * FS).roundToInt()
        var sum: DoubleArray? = null
        for (k in 0 until nReg) {
            val r = traceOf(used, { it.regions[k] }, 0) ?: return null
            val g = traceOf(used, { it.regions[k] }, 1) ?: return null
            val b = traceOf(used, { it.regions[k] }, 2) ?: return null
            val lo = (centreHz - halfBandHz).coerceAtLeast(0.7); val hi = (centreHz + halfBandHz).coerceAtMost(3.0)
            // applied twice: one pass of a single band-pass is too loose to keep noise peaks out of a band this narrow
            val f = Dsp.bandpass(Dsp.bandpass(FaceRppg.pos(r, g, b), FS, 0.7, 3.0), FS, lo, hi).let { Dsp.bandpass(it, FS, lo, hi) }
            if (f.size <= 4 * trim) return null
            val core = f.copyOfRange(trim, f.size - trim)
            val sd = Dsp.std(core).coerceAtLeast(1e-12)
            val s = sum
            sum = if (s == null) DoubleArray(core.size) { core[it] / sd } else DoubleArray(core.size) { s[it] + core[it] / sd }
        }
        return sum
    }
}
