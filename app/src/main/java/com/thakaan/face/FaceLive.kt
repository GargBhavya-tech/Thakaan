package com.thakaan.face

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.thakaan.ppg.GuardDecision
import com.thakaan.ppg.ThermalGuard

/** What the live screen draws: the latest picture, where the face and skin regions are, and the latest vitals. */
class LiveView(val picture: Bitmap?, val observation: FaceObservation?, val frameNo: Int, val vitals: VitalsSnapshot?, val cameraLocked: Boolean)

/** The worker's own pulse-oximeter reading, turned into a single offset for the optical oxygen estimate. Stored on the phone only. */
class OxygenCalibrationStore(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("thakaan_face", Context.MODE_PRIVATE)
    fun offsetPct(): Double? = if (prefs.contains("o2_offset")) prefs.getFloat("o2_offset", 0f).toDouble() else null
    fun saveOffset(pct: Double) { prefs.edit().putFloat("o2_offset", pct.toFloat()).apply() }
    fun clear() { prefs.edit().remove("o2_offset").apply() }
}

/**
 * Live face view: the front camera runs until stopped, the picture is shown to the worker with what is being read drawn on
 * it, and the vitals (pulse, breathing, variability, oxygen estimate, eye and yawn signs) are recomputed once a second from
 * the last 75 s. Everything stays in memory and is dropped on stop (invariant 7); nothing here reaches the risk engine and
 * none of it can lower a level. Oxygen is an uncalibrated experiment and is labelled that way on screen.
 */
class FaceLiveSession(private val ctx: Context) {
    private val tracker = FaceTracker(ctx)
    private val camera = FrontCameraCapture(ctx)
    private val frames = ArrayList<FaceFrame>()
    private val fatigue = ArrayList<FatigueFrame>()
    private val cal = FatigueCalibrationStore(ctx).load() ?: FatigueCalibration()
    private val o2 = OxygenCalibrationStore(ctx)
    private var firstT = 0L
    private var shown: Bitmap? = null
    private var frameNo = 0
    @Volatile private var lastObs: FaceObservation? = null
    @Volatile private var vitals: VitalsSnapshot? = null
    @Volatile private var error: String? = null
    private var lastComputeMs = 0L
    @Volatile private var computing = false
    private var lastRawEye: Pair<Double, Double>? = null

    fun error(): String? = error
    fun oxygenOffset(): Double? = o2.offsetPct()

    /** Returns null when the camera is running, or the reason it could not start. */
    fun start(): String? {
        val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
        val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else null
        val g: GuardDecision = ThermalGuard.captureAllowed(pm.currentThermalStatus, headroom)
        if (!g.allowed) return g.prompt ?: "Phone is too hot for this."
        tracker.open()?.let { return "face model: $it" }
        camera.start(
            onFrame = { t, bmp, px ->
                if (firstT == 0L) firstT = t
                val obs = tracker.track(bmp, px, t)
                lastObs = obs
                synchronized(this) {
                    frames += obs.toFaceFrame(); fatigue += obs.toFatigueFrame()
                    if (obs.hasBlend) lastRawEye = obs.eyeClosure to obs.jawOpen
                    val cut = t - KEEP_SEC * 1_000_000_000L
                    while (frames.size > 1 && frames.first().tNanos < cut) frames.removeAt(0)
                    while (fatigue.size > 1 && fatigue.first().tNanos < cut) fatigue.removeAt(0)
                }
                frameNo++
                // every 3rd frame the picture is copied for the screen (10 fps is plenty to look at)
                if (frameNo % 3 == 0) {
                    val copy = shown?.takeIf { it.width == bmp.width && it.height == bmp.height }
                        ?: Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888).also { shown = it }
                    android.graphics.Canvas(copy).drawBitmap(bmp, 0f, 0f, null)
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastComputeMs >= 1000 && !computing) {
                    lastComputeMs = now; computing = true
                    Thread {
                        try {
                            val f: List<FaceFrame>; val a: List<FatigueFrame>; val raw: Pair<Double, Double>?
                            synchronized(this) { f = frames.toList(); a = fatigue.toList(); raw = if (lastObs?.box != null) lastRawEye else null }
                            vitals = FaceVitals.snapshot(f, a, cal, raw, o2.offsetPct())
                        } catch (_: Throwable) {
                        } finally { computing = false }
                    }.start()
                }
            },
            onError = { error = it },
        )
        return null
    }

    fun view(): LiveView = LiveView(shown, lastObs, frameNo, vitals, camera.applied?.aeLocked == true)

    fun stop() {
        camera.stop(); tracker.close()
        synchronized(this) { frames.clear(); fatigue.clear() }
        shown = null; vitals = null; lastObs = null
    }

    companion object { const val KEEP_SEC = 75L }
}
