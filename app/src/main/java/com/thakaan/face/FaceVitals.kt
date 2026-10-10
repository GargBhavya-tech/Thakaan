package com.thakaan.face

import com.thakaan.ppg.Dsp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Beat-to-beat variability from the face pulse. Rough: 30 fps video limits the timing, so read it as a trend, not a lab value. */
class Hrv(val rmssdMs: Double, val sdnnMs: Double, val meanHrBpm: Double, val beats: Int)

/**
 * Optical "oxygen" signals from skin colour. [ratio] is the red-to-blue ratio of ratios (pulse strength relative to the
 * steady level, red over blue). [estimatePct] maps it with a textbook straight line, plus the worker's own calibration
 * offset if they entered a pulse-oximeter reading. UNCALIBRATED and EXPERIMENTAL: shown to the worker with that label,
 * never fed to the risk engine, never a medical reading. [perfusionPct] is the green pulse strength as a share of the level.
 */
class Oxygen(val ratio: Double, val estimatePct: Double, val perfusionPct: Double, val calibrated: Boolean)

/** One second's view of everything the face gives. Every field can be null: "no information" never means "fine". */
class VitalsSnapshot(
    val seconds: Double,
    val facePercent: Int,
    val pulse: FaceRppgResult?,
    val hrv: Hrv?,
    val hrvWaitSec: Int,
    val oxygen: Oxygen?,
    val fatigue: FatigueSummary?,
    /** Current eyelid closure 0 (open) to 1 (closed) after the worker's own calibration; null with no face. */
    val eyeClosureNow: Double?,
    val jawOpenNow: Double?,
    val brightness: Double,
    val motion: Double,
)

object FaceVitals {
    const val HRV_SECONDS = 60.0
    const val PULSE_WINDOW_SEC = 40.0
    const val MIN_RR_DIFFS = 25

    /** Beat times (s) from a band-passed pulse wave: local maxima at least 0.33 s apart, refined to a fraction of a sample. */
    fun beatTimes(x: DoubleArray, fs: Double): List<Double> {
        if (x.size < 3) return emptyList()
        val sd = Dsp.std(x)
        if (sd < 1e-12) return emptyList()
        val minGap = (0.33 * fs).roundToInt()
        val peaks = ArrayList<Int>()
        for (i in 1 until x.size - 1) {
            if (x[i] > 0.25 * sd && x[i] > x[i - 1] && x[i] >= x[i + 1]) {
                if (peaks.isEmpty() || i - peaks.last() >= minGap) peaks += i
                else if (x[i] > x[peaks.last()]) peaks[peaks.size - 1] = i
            }
        }
        return peaks.map { i ->
            val y0 = x[i - 1]; val y1 = x[i]; val y2 = x[i + 1]
            val den = y0 - 2 * y1 + y2
            val d = if (abs(den) < 1e-12) 0.0 else (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5)
            (i + d) / fs
        }
    }

    /** RMSSD and SDNN from beat times. Intervals far from the median (missed or extra beats) are dropped, not averaged in. */
    fun hrv(beats: List<Double>): Hrv? {
        if (beats.size < 10) return null
        val rr = beats.zipWithNext { a, b -> (b - a) * 1000.0 }
        val med = rr.sorted()[rr.size / 2]
        val ok = rr.map { it in 0.75 * med..1.25 * med && it in 400.0..1500.0 }
        val diffs = ArrayList<Double>()
        for (i in 1 until rr.size) if (ok[i] && ok[i - 1]) diffs += rr[i] - rr[i - 1]
        val good = rr.filterIndexed { i, _ -> ok[i] }
        // a real pulse has mostly plausible intervals; noise gives scattered ones, so most must survive the filter
        if (diffs.size < MIN_RR_DIFFS || good.size < MIN_RR_DIFFS || good.size < 0.8 * rr.size) return null
        val rmssd = sqrt(diffs.sumOf { it * it } / diffs.size)
        val mean = good.average()
        val sdnn = sqrt(good.sumOf { (it - mean) * (it - mean) } / good.size)
        return Hrv(rmssd, sdnn, 60000.0 / mean, good.size + 1)
    }

    /** Ratio of ratios from three uniformly sampled colour traces. Needs a pulse already confirmed by the caller. */
    fun oxygen(r: DoubleArray, g: DoubleArray, b: DoubleArray, fs: Double, calibrationOffsetPct: Double? = null): Oxygen? {
        if (r.size < fs * 10) return null
        val trim = (FaceRppg.POS_WINDOW_SEC * fs).roundToInt().coerceAtMost(r.size / 4)
        fun core(x: DoubleArray): DoubleArray { val f = Dsp.bandpass(x, fs, 0.7, 3.0); return f.copyOfRange(trim, f.size - trim) }
        fun level(x: DoubleArray) = x.copyOfRange(trim, x.size - trim).average()
        val dcR = level(r); val dcG = level(g); val dcB = level(b)
        if (dcR < 1.0 || dcG < 1.0 || dcB < 1.0) return null
        val acR = Dsp.std(core(r)); val acG = Dsp.std(core(g)); val acB = Dsp.std(core(b))
        if (acB < 1e-9 || acR < 1e-9) return null
        val ratio = (acR / dcR) / (acB / dcB)
        val offset = calibrationOffsetPct ?: 0.0
        val est = (110.0 - 25.0 * ratio + offset).coerceIn(70.0, 100.0)
        return Oxygen(ratio, est, acG / dcG * 100.0, calibrationOffsetPct != null)
    }

    /** Uniform 30 Hz colour traces of the frames where a face was found. */
    private fun traces(used: List<FaceFrame>): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val t = DoubleArray(used.size) { (used[it].tNanos - used[0].tNanos) / 1e9 }
        return Triple(
            Dsp.resampleUniform(t, DoubleArray(used.size) { used[it].r }, FaceRppg.FS).second,
            Dsp.resampleUniform(t, DoubleArray(used.size) { used[it].g }, FaceRppg.FS).second,
            Dsp.resampleUniform(t, DoubleArray(used.size) { used[it].b }, FaceRppg.FS).second,
        )
    }

    /** Everything for the live screen, from the rolling buffers. Pure: tested on the laptop with synthetic faces. */
    fun snapshot(
        frames: List<FaceFrame>, fatigue: List<FatigueFrame>, cal: FatigueCalibration, rawNow: Pair<Double, Double>?,
        oxygenOffsetPct: Double?,
    ): VitalsSnapshot {
        if (frames.size < 2) return VitalsSnapshot(0.0, 0, null, null, HRV_SECONDS.toInt(), null, null, null, null, 0.0, 0.0)
        val tEnd = frames.last().tNanos
        val seconds = (tEnd - frames.first().tNanos) / 1e9
        val facePct = (100.0 * frames.count { it.faceFound } / frames.size).roundToInt()
        val recent = frames.filter { (tEnd - it.tNanos) / 1e9 <= PULSE_WINDOW_SEC }
        // the per-region method when the frames carry region means, the pooled one otherwise
        val base = FaceRppgRegions.analyse(recent) ?: FaceRppg.analyse(recent)
        val recentUsed = recent.filter { it.faceFound }
        val resp = if (recentUsed.size > 300) {
            val t = DoubleArray(recentUsed.size) { (recentUsed[it].tNanos - recentUsed[0].tNanos) / 1e9 }
            try { FaceRppg.respiration(Dsp.resampleUniform(t, DoubleArray(recentUsed.size) { recentUsed[it].g }, FaceRppg.FS).second) } catch (_: IllegalArgumentException) { null }
        } else null
        val pulse = FaceRppgResult(base.accepted, base.hrBpm, resp ?: base.respPerMin, base.snrDb, base.confidence, base.seconds, base.reasons, base.estimateBpm)
        val used = frames.filter { it.faceFound }
        val bright = recent.filter { it.faceFound }.let { l -> if (l.isEmpty()) 0.0 else l.sumOf { (it.r + it.g + it.b) / 3 } / l.size }
        val motion = if (recent.isEmpty()) 0.0 else Dsp.median(DoubleArray(recent.size) { recent[it].motion })

        var hrv: Hrv? = null
        if (pulse.accepted && used.size > 90) {
            val longWin = used.filter { (tEnd - it.tNanos) / 1e9 <= HRV_SECONDS + 5 }
            val span = (longWin.last().tNanos - longWin.first().tNanos) / 1e9
            if (span >= HRV_SECONDS) {
                val (r, g, b) = traces(longWin)
                val trim = (FaceRppg.POS_WINDOW_SEC * FaceRppg.FS).roundToInt()
                val w = Dsp.bandpass(FaceRppg.pos(r, g, b), FaceRppg.FS, 0.7, 3.0)
                if (w.size > 4 * trim) hrv = hrv(beatTimes(w.copyOfRange(trim, w.size - trim), FaceRppg.FS))
            }
        }
        val waitSec = if (hrv != null) 0 else (HRV_SECONDS - seconds).coerceAtLeast(0.0).roundToInt()

        var oxygen: Oxygen? = null
        val usedRecent = recent.filter { it.faceFound }
        if (pulse.accepted && usedRecent.size > 300) {
            val (r, g, b) = traces(usedRecent)
            oxygen = try { oxygen(r, g, b, FaceRppg.FS, oxygenOffsetPct) } catch (_: IllegalArgumentException) { null }
        }

        val fat = fatigue.filter { (fatigue.last().tNanos - it.tNanos) / 1e9 <= PULSE_WINDOW_SEC }
        val summary = if (fat.size > 30) FatigueSignals.analyse(fat, cal) else null
        return VitalsSnapshot(
            seconds, facePct, pulse, hrv, waitSec, oxygen, summary,
            rawNow?.let { cal.normalise(it.first) }, rawNow?.second, bright, motion,
        )
    }
}
