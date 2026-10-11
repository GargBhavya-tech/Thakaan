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
class FaceLive(
    val state: FrameState, val secondsLeft: Int, val facePercent: Int, val cameraLocked: Boolean,
    /** The latest picture and what was found in it, for the screen to draw; null in the light mode. */
    val picture: android.graphics.Bitmap? = null, val observation: FaceObservation? = null, val frameNo: Int = 0,
    /** The pulse read from the last seconds so far, accepted or not; null until there is enough to read. */
    val pulse: FaceRppgResult? = null,
)

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
class FaceCheckSession(private val ctx: Context, private val lite: Boolean = false) {
    private val tracker = FaceTracker(ctx)
    private val camera = FrontCameraCapture(ctx)
    private val faceFrames = ArrayList<FaceFrame>()
    private val fatigueFrames = ArrayList<FatigueFrame>()
    private val rawClosure = ArrayList<Double>()
    private val learned by lazy { com.thakaan.ml.LearnedPulseRunner(ctx) }
    private val crops = ArrayList<ByteArray>()
    private var firstT = 0L
    private var startedElapsedMs = 0L
    @Volatile private var live = FaceLive(FrameState.NO_FACE, SETTLE_SEC + CAPTURE_SEC, 0, false)
    @Volatile private var error: String? = null
    private var seen = 0
    private var found = 0
    // the picture and a pulse that is re-read every 2 s from the last seconds (full mode only), so the worker sees the face and the pulse lock
    private var shown: android.graphics.Bitmap? = null
    private var frameNo = 0
    @Volatile private var pulseNow: FaceRppgResult? = null
    @Volatile private var computing = false
    private var lastComputeMs = 0L

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
                        if (!lite && box != null && learned.available) crops += learned.crop(bmp, box) else if (box == null && crops.size < MIN_RUN) crops.clear()
                    }
                }
                frameNo++
                if (!lite) {
                    // every 3rd frame the picture is copied for the screen (10 fps is plenty to look at)
                    if (frameNo % 3 == 0) {
                        val copy = shown?.takeIf { it.width == bmp.width && it.height == bmp.height }
                            ?: android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888).also { shown = it }
                        android.graphics.Canvas(copy).drawBitmap(bmp, 0f, 0f, null)
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (elapsed >= SETTLE_SEC + 10 && now - lastComputeMs >= 2000 && !computing) {
                        lastComputeMs = now; computing = true
                        Thread {
                            try {
                                val recent = synchronized(this) { recentFrames(faceFrames) }
                                pulseNow = analysePulse(recent)
                            } catch (_: Throwable) {
                            } finally { computing = false }
                        }.start()
                    }
                }
                val total = if (lite) SETTLE_SEC + CAPTURE_SEC else SETTLE_SEC + MAX_SEC
                live = FaceLive(
                    FrameGuideRules.guide(obs.box).state, (total - elapsed).toInt().coerceAtLeast(0),
                    if (seen == 0) 0 else 100 * found / seen, camera.applied?.aeLocked == true,
                    if (lite) null else shown, if (lite) null else obs, frameNo, pulseNow,
                )
            },
            onError = { error = it },
        )
        return null
    }

    fun elapsedSec(): Double = (SystemClock.elapsedRealtime() - startedElapsedMs) / 1000.0
    /**
     * The light mode (riding) always runs [CAPTURE_SEC]. The full mode keeps reading until the pulse is confirmed (after at least
     * [MIN_SEC]) or [MAX_SEC] has passed, so a moment of drift does not fail the whole check.
     */
    fun done(): Boolean {
        if (error != null) return true
        val t = elapsedSec()
        if (lite) return t >= SETTLE_SEC + CAPTURE_SEC + 1
        return t >= SETTLE_SEC + MAX_SEC + 1 || (t >= SETTLE_SEC + MIN_SEC && pulseNow?.accepted == true)
    }

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
        val rppg = if (lite) null else analysePulse(recentFrames(ff))
        val fatigue = FatigueSignals.analyse(tf, newCal ?: calibration ?: FatigueCalibration())
        val seconds = if (ff.size > 1) (ff.last().tNanos - ff.first().tNanos) / 1e9 else 0.0
        val fps = if (seconds > 1.0) ff.size / seconds else 30.0
        val lp = if (!lite && cr.size > MIN_RUN) learned.analyse(cr.takeLast(1100), fps) else null
        if (!lite) learned.close()
        return FaceCheckOutcome(null, rppg, fatigue, seconds, ff.size, newCal, lp)
    }

    fun abort() { camera.stop(); tracker.close(); if (!lite) learned.close() }

    companion object {
        const val SETTLE_SEC = 3
        const val CAPTURE_SEC = 35
        /** Full mode: earliest and latest end of the measured part, and how far back the pulse is read. */
        const val MIN_SEC = 33
        const val MAX_SEC = 60
        const val PULSE_WINDOW_SEC = 45.0

        /** The last [PULSE_WINDOW_SEC] seconds of frames. */
        fun recentFrames(f: List<FaceFrame>): List<FaceFrame> {
            val end = f.lastOrNull()?.tNanos ?: return f
            return f.filter { (end - it.tNanos) / 1e9 <= PULSE_WINDOW_SEC }
        }

        /** The same reading the live view makes: per-region when the frames carry regions, pooled otherwise. */
        fun analysePulse(f: List<FaceFrame>): FaceRppgResult = FaceRppgRegions.analyse(f) ?: FaceRppg.analyse(f)
        private const val MIN_RUN = 150
    }
}

private fun thermalDecision(ctx: Context): GuardDecision {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else null
    return ThermalGuard.captureAllowed(pm.currentThermalStatus, headroom)
}
