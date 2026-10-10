package com.thakaan.face

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.thakaan.ppg.GuardDecision
import com.thakaan.ppg.ThermalGuard

/** The result of one face check. Every part can be "no information", and none of it can lower a level. */
class FaceCheckOutcome(
    val error: String?,
    val rppg: FaceRppgResult?,
    val fatigue: FatigueSummary?,
    val seconds: Double,
    val frames: Int,
    /** Present only when this check was marked as the worker's rested baseline and the eyelid range was usable. */
    val newCalibration: FatigueCalibration?,
    /** The learned (EfficientPhys) face pulse, shown beside the POS pulse. Display only; null when the model or the data is missing. */
    val learnedPulse: com.thakaan.ml.LearnedPulse? = null,
)

/** What the screen shows while the check runs. */
class FaceLive(val state: FrameState, val secondsLeft: Int, val facePercent: Int, val cameraLocked: Boolean)

/** The worker's own open-eye and closed-eye levels, kept on the phone only. */
class FatigueCalibrationStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("thakaan_face", Context.MODE_PRIVATE)
    fun load(): FatigueCalibration? {
        if (!prefs.contains("open") || !prefs.contains("closed")) return null
        return try { FatigueCalibration(prefs.getFloat("open", 0f).toDouble(), prefs.getFloat("closed", 1f).toDouble()) } catch (_: Exception) { null }
    }
    fun save(c: FatigueCalibration) { prefs.edit().putFloat("open", c.open.toFloat()).putFloat("closed", c.closed.toFloat()).apply() }
}

/**
 * One optional face check (#31): 3 s for the camera to settle (ignored), then [CAPTURE_SEC] s measured. Reads the pulse
 * (POS) and breathing from the skin colour and the fatigue signals from the eyelid, jaw and gaze scores, all in memory.
 * It never replaces the fingertip check and never lowers a level.
 */
class FaceCheckSession(private val ctx: Context) {
    private val tracker = FaceTracker(ctx)
    private val camera = FrontCameraCapture(ctx)
    private val faceFrames = ArrayList<FaceFrame>()
    private val fatigueFrames = ArrayList<FatigueFrame>()
    private val rawClosure = ArrayList<Double>()
    private val learned = com.thakaan.ml.LearnedPulseRunner(ctx)
    private val crops = ArrayList<ByteArray>()
    private var firstT = 0L
    private var startedElapsedMs = 0L
    @Volatile private var live = FaceLive(FrameState.NO_FACE, SETTLE_SEC + CAPTURE_SEC, 0, false)
    @Volatile private var error: String? = null
    private var seen = 0
    private var found = 0

    fun live(): FaceLive = live
    fun cameraInfo(): FrontCameraCapture.Applied? = camera.applied

    /** Returns null when the check is running, or the reason it could not start. */
    fun start(): String? {
        val g = thermalDecision(ctx)
        if (!g.allowed) return g.prompt ?: "Phone is too hot for this check."
        tracker.open()?.let { return "face model: $it" }
        startedElapsedMs = SystemClock.elapsedRealtime()
        camera.start(
            onFrame = { t, bmp, px ->
                if (firstT == 0L) firstT = t
                val obs = tracker.track(bmp, px, t)
                val elapsed = (t - firstT) / 1e9
                seen++; if (obs.box != null) found++
                if (elapsed >= SETTLE_SEC) {
                    synchronized(this) {
                        faceFrames += obs.toFaceFrame()
                        fatigueFrames += obs.toFatigueFrame()
                        if (obs.hasBlend) rawClosure += obs.eyeClosure
                        // consecutive crops only: a lost face starts a new run, and only the longest run is used
                        val box = obs.box
                        if (box != null && learned.available) crops += learned.crop(bmp, box) else if (box == null && crops.size < MIN_RUN) crops.clear()
                    }
                }
                live = FaceLive(
                    FrameGuideRules.guide(obs.box).state, (SETTLE_SEC + CAPTURE_SEC - elapsed).toInt().coerceAtLeast(0),
                    if (seen == 0) 0 else 100 * found / seen, camera.applied?.aeLocked == true,
                )
            },
            onError = { error = it },
        )
        return null
    }

    fun elapsedSec(): Double = (SystemClock.elapsedRealtime() - startedElapsedMs) / 1000.0
    fun done(): Boolean = error != null || elapsedSec() >= SETTLE_SEC + CAPTURE_SEC + 1

    /** Stops the camera, analyses what was captured, and drops it all. [baseline] true saves the eyelid range as this worker's own. */
    fun finish(calibration: FatigueCalibration?, baseline: Boolean): FaceCheckOutcome {
        camera.stop(); tracker.close()
        val err = error
        val ff: List<FaceFrame>; val tf: List<FatigueFrame>; val raw: DoubleArray; val cr: List<ByteArray>
        synchronized(this) {
            ff = faceFrames.toList(); tf = fatigueFrames.toList(); raw = rawClosure.toDoubleArray(); cr = crops.toList()
            faceFrames.clear(); fatigueFrames.clear(); rawClosure.clear(); crops.clear()
        }
        if (err != null) return FaceCheckOutcome(err, null, null, 0.0, ff.size, null)
        val newCal = if (baseline) FatigueCalibration.fromSamples(raw) else null
        val rppg = FaceRppgRegions.analyse(ff) ?: FaceRppg.analyse(ff)
        val fatigue = FatigueSignals.analyse(tf, newCal ?: calibration ?: FatigueCalibration())
        val seconds = if (ff.size > 1) (ff.last().tNanos - ff.first().tNanos) / 1e9 else 0.0
        val fps = if (seconds > 1.0) ff.size / seconds else 30.0
        val lp = if (cr.size > MIN_RUN) learned.analyse(cr, fps) else null
        learned.close()
        return FaceCheckOutcome(null, rppg, fatigue, seconds, ff.size, newCal, lp)
    }

    fun abort() { camera.stop(); tracker.close(); learned.close() }

    companion object {
        const val SETTLE_SEC = 3
        const val CAPTURE_SEC = 35
        private const val MIN_RUN = 150
    }
}

private fun thermalDecision(ctx: Context): GuardDecision {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else null
    return ThermalGuard.captureAllowed(pm.currentThermalStatus, headroom)
}
