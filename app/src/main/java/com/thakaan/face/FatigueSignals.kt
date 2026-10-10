package com.thakaan.face

import com.thakaan.brain.BrainResult
import com.thakaan.brain.BrainTest
import com.thakaan.ppg.Dsp
import kotlin.math.sqrt

/**
 * One frame of face-fatigue evidence (N8). [eyeClosure] is the mean of the two eye-blink scores (0 open, 1 closed),
 * [jawOpen] is 0..1, [gazeX] and [gazeY] are the signed gaze direction (about -1..1). All come from the landmarker's
 * blendshape scores; nothing about identity, emotion or appearance is read. [faceFound] false means "no information".
 */
class FatigueFrame(val tNanos: Long, val eyeClosure: Double, val jawOpen: Double, val gazeX: Double, val gazeY: Double, val faceFound: Boolean)

/**
 * A person's own open-eye and closed-eye levels. Blink scores differ between people (heavy lids, glasses, angle), so fixed
 * thresholds do not travel; the literature recommends per-person calibration. [open] and [closed] are raw scores.
 */
class FatigueCalibration(val open: Double = 0.08, val closed: Double = 0.85) {
    init { require(closed - open >= 0.2) { "calibration range too small" } }

    fun normalise(raw: Double): Double = ((raw - open) / (closed - open)).coerceIn(0.0, 1.0)

    companion object {
        /** From a few alert seconds that include some blinks: open = 10th percentile, closed = 99th percentile. Null if the range is too small to trust. */
        fun fromSamples(raw: DoubleArray): FatigueCalibration? {
            if (raw.size < 60) return null
            val open = Dsp.percentile(raw, 10.0)
            val closed = Dsp.percentile(raw, 99.0)
            return if (closed - open >= 0.2) FatigueCalibration(open, closed) else null
        }
    }
}

enum class FatigueReason(val prompt: String) {
    TOO_SHORT("Keep looking at the phone a little longer"),
    NO_FACE("Keep your face in the frame"),
    SQUINTING("Bright light is making you squint: try in shade"),
}

class FatigueSummary(
    val valid: Boolean,
    val reason: FatigueReason?,
    val seconds: Double,
    val blinksPerMin: Double,
    /** Share of the window with the eyelid at least 80% closed (the "time at 80% or more closed" PERCLOS definition). */
    val perclos: Double,
    /** Closures longer than half a second: sleepiness candidates. */
    val longClosures: Int,
    val yawns: Int,
    val yawnsPerMin: Double,
    /** Spread of the gaze direction while the eyes were open (0 steady). */
    val gazeDrift: Double,
)

object FatigueSignals {
    const val MIN_SECONDS = 30.0
    const val MIN_FACE_FRACTION = 0.7
    const val CLOSE_ENTER = 0.5
    const val CLOSE_EXIT = 0.35
    const val PERCLOS_LEVEL = 0.8
    const val MIN_BLINK_SEC = 0.05
    const val LONG_CLOSURE_SEC = 0.5
    const val YAWN_JAW = 0.6
    const val YAWN_MIN_SEC = 1.5
    const val YAWN_MERGE_GAP_SEC = 0.2
    /** More than this share of frames half-closed (0.3 to 0.6) looks like squinting into light, not tiredness. */
    const val SQUINT_FRACTION = 0.5

    private fun invalid(reason: FatigueReason, seconds: Double) = FatigueSummary(false, reason, seconds, 0.0, 0.0, 0, 0, 0.0, 0.0)

    fun analyse(frames: List<FatigueFrame>, cal: FatigueCalibration = FatigueCalibration()): FatigueSummary {
        if (frames.size < 10) return invalid(FatigueReason.TOO_SHORT, 0.0)
        val seconds = (frames.last().tNanos - frames.first().tNanos) / 1e9
        if (seconds < MIN_SECONDS) return invalid(FatigueReason.TOO_SHORT, seconds)
        val found = frames.count { it.faceFound }
        if (found.toDouble() / frames.size < MIN_FACE_FRACTION) return invalid(FatigueReason.NO_FACE, seconds)

        val c = DoubleArray(frames.size) { cal.normalise(frames[it].eyeClosure) }
        val ok = BooleanArray(frames.size) { frames[it].faceFound }

        val half = frames.indices.count { ok[it] && c[it] in 0.3..0.6 }
        if (half.toDouble() / found > SQUINT_FRACTION) return invalid(FatigueReason.SQUINTING, seconds)

        // time-weighted PERCLOS over frames where a face was found
        var tot = 0.0; var closed = 0.0
        for (i in 1 until frames.size) {
            if (!ok[i] || !ok[i - 1]) continue
            val dt = (frames[i].tNanos - frames[i - 1].tNanos) / 1e9
            if (dt <= 0.0 || dt > 0.25) continue
            tot += dt
            if (c[i] >= PERCLOS_LEVEL) closed += dt
        }
        val perclos = if (tot > 0) closed / tot else 0.0

        // closure events with hysteresis, never across a lost-face gap
        var blinks = 0; var longClosures = 0
        var startT = -1L; var inClosure = false; var lastOkT = -1L
        for (i in frames.indices) {
            if (!ok[i]) {
                // the face went away mid-closure: count only the part actually seen, never bridge the gap
                if (inClosure && (lastOkT - startT) / 1e9 > LONG_CLOSURE_SEC) longClosures++
                inClosure = false; startT = -1L; continue
            }
            lastOkT = frames[i].tNanos
            if (!inClosure && c[i] >= CLOSE_ENTER) { inClosure = true; startT = frames[i].tNanos }
            else if (inClosure && c[i] < CLOSE_EXIT) {
                val dur = (frames[i].tNanos - startT) / 1e9
                if (dur > LONG_CLOSURE_SEC) longClosures++ else if (dur >= MIN_BLINK_SEC) blinks++
                inClosure = false
            }
        }
        if (inClosure && startT >= 0) {
            val dur = (frames.last().tNanos - startT) / 1e9
            if (dur > LONG_CLOSURE_SEC) longClosures++
        }

        val yawns = countYawns(frames, ok)

        // gaze drift only while the eyes are open: a closed eye has no meaningful gaze
        val gx = ArrayList<Double>(); val gy = ArrayList<Double>()
        for (i in frames.indices) if (ok[i] && c[i] < CLOSE_EXIT) { gx += frames[i].gazeX; gy += frames[i].gazeY }
        val drift = if (gx.size < 10) 0.0 else sqrt(var2(gx) + var2(gy))

        val minutes = seconds / 60.0
        return FatigueSummary(true, null, seconds, blinks / minutes, perclos, longClosures, yawns, yawns / minutes, drift)
    }

    private fun var2(x: List<Double>): Double {
        val m = x.average()
        return x.sumOf { (it - m) * (it - m) } / x.size
    }

    /** A yawn is the jaw wide open for at least 1.5 s; short gaps inside one yawn are merged; speech and chewing are shorter. */
    private fun countYawns(frames: List<FatigueFrame>, ok: BooleanArray): Int {
        var yawns = 0
        var startT = -1L; var lastOpenT = -1L
        fun close() {
            if (startT >= 0 && (lastOpenT - startT) / 1e9 >= YAWN_MIN_SEC) yawns++
            startT = -1L; lastOpenT = -1L
        }
        for (i in frames.indices) {
            if (!ok[i]) { close(); continue }
            val t = frames[i].tNanos
            if (frames[i].jawOpen >= YAWN_JAW) {
                if (startT < 0) startT = t
                lastOpenT = t
            } else if (startT >= 0 && (t - lastOpenT) / 1e9 > YAWN_MERGE_GAP_SEC) close()
        }
        close()
        return yawns
    }

    /**
     * What the brain battery gets from one window. Only a valid window gives results; a squint, a lost face or a short
     * window gives none, and "no result" is never read as "fine". The phone is mounted for this test, so placement is "mounted".
     */
    fun toBrainResults(s: FatigueSummary, tNanos: Long, hourOfDay: Double, deviceId: String): List<BrainResult> =
        if (!s.valid) emptyList() else listOf(
            BrainResult(BrainTest.FACE_PERCLOS, s.perclos, tNanos, hourOfDay, deviceId, "mounted"),
            BrainResult(BrainTest.FACE_YAWN_RATE, s.yawnsPerMin, tNanos, hourOfDay, deviceId, "mounted"),
        )
}
