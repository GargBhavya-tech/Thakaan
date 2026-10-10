package com.thakaan.face

import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.PowerManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.io.File
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

    // optional recording of the colour numbers (never pictures) so the pulse method can be tuned on real traces
    private class Rec(val t: Long, val r: Double, val g: Double, val b: Double, val found: Boolean, val motion: Double, val eye: Double, val jaw: Double, val rois: List<DoubleArray>, val bg: DoubleArray?)
    private val rec = ArrayList<Rec>()
    private val recAcc = ArrayList<FloatArray>()
    @Volatile var recording = false; private set
    private var recStartT = 0L
    private var accListener: SensorEventListener? = null

    private fun extra(p: Rec): String {
        fun tri(a: DoubleArray?) = if (a == null) ",,," else ",%.3f,%.3f,%.3f".format(a[0], a[1], a[2])
        return tri(p.rois.getOrNull(0)) + tri(p.rois.getOrNull(1)) + tri(p.rois.getOrNull(2)) + tri(p.bg)
    }

    fun recordedSeconds(): Int = synchronized(rec) { if (rec.size < 2) 0 else ((rec.last().t - rec.first().t) / 1_000_000_000L).toInt() }

    fun startRecording() {
        synchronized(rec) { rec.clear(); recAcc.clear() }
        recStartT = 0L
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { synchronized(rec) { recAcc += floatArrayOf((SystemClock.elapsedRealtimeNanos() / 1_000_000L).toFloat(), e.values[0], e.values[1], e.values[2]) } }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        accListener = l
        recording = true
    }

    /** Stops and writes two small CSV files in the app's private folder (colour means over time, and phone motion). Returns the file name. */
    fun stopRecording(): String? {
        recording = false
        (ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager).let { sm -> accListener?.let { sm.unregisterListener(it) } }
        accListener = null
        val r: List<Rec>; val a: List<FloatArray>
        synchronized(rec) { r = rec.toList(); a = recAcc.toList(); rec.clear(); recAcc.clear() }
        if (r.size < 30) return null
        val base = "face_live_${System.currentTimeMillis()}"
        val locked = camera.applied?.aeLocked == true
        File(ctx.filesDir, "$base.csv").bufferedWriter().use { w ->
            w.write("t_ms,r,g,b,face,motion,eye_closure,jaw_open,ae_locked,f_r,f_g,f_b,l_r,l_g,l_b,rc_r,rc_g,rc_b,bg_r,bg_g,bg_b\n")
            val t0 = r.first().t
            for (p in r) w.write("%d,%.3f,%.3f,%.3f,%d,%.4f,%.3f,%.3f,%d%s\n".format((p.t - t0) / 1_000_000L, p.r, p.g, p.b, if (p.found) 1 else 0, p.motion, p.eye, p.jaw, if (locked) 1 else 0, extra(p)))
        }
        File(ctx.filesDir, "${base}_acc.csv").bufferedWriter().use { w ->
            w.write("t_ms,ax,ay,az\n")
            val tA = a.firstOrNull()?.get(0) ?: 0f
            for (p in a) w.write("%.0f,%.3f,%.3f,%.3f\n".format(p[0] - tA, p[1], p[2], p[3]))
        }
        return "$base.csv"
    }

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
                if (recording) synchronized(rec) {
                    if (rec.size < MAX_REC_FRAMES) rec += Rec(t, obs.r, obs.g, obs.b, obs.box != null, obs.motion, obs.eyeClosure, obs.jawOpen, obs.roiMeans, obs.background)
                }
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
        if (recording) stopRecording()
        camera.stop(); tracker.close()
        synchronized(this) { frames.clear(); fatigue.clear() }
        shown = null; vitals = null; lastObs = null
    }

    companion object { const val KEEP_SEC = 75L; const val MAX_REC_FRAMES = 30 * 180 }
}
