package com.thakaan.ride

import android.content.Context
import android.os.Build
import android.os.PowerManager
import com.thakaan.face.FaceCheckSession
import com.thakaan.face.FatigueCalibrationStore
import com.thakaan.ui.saveFaceResults

/** What the last camera window said about the rider's eyes. */
enum class Alertness(val word: String) { WAITING("Not read yet"), ALERT("Alert"), DROWSY("Drowsy signs"), NO_FACE("Face not seen") }

/**
 * Runs the front camera in short windows while the rider rides, as told by [RideCameraPolicy]. Each window reads only the eyes and
 * yawns (the light mode of [FaceCheckSession]: no pulse, no learned model), so it costs the phone as little as possible. A
 * finished window feeds the same eyelid and yawn signals to the brain battery as a face check does, against the rider's own
 * baseline, so the strain score follows how the rider looks; it never lowers a level. Frames stay in memory and are dropped.
 */
class RideMonitor(private val ctx: Context) {
    private val store = FatigueCalibrationStore(ctx)
    private var session: FaceCheckSession? = null
    private var startedThisWindow = false

    var alertness = Alertness.WAITING; private set
    /** True after a window with drowsy signs until a clean window follows: the policy then watches more often. */
    var drowsyRecently = false; private set
    var windows = 0; private set
    var drowsyWindows = 0; private set
    /** What to tell the rider when the face could not be seen (for example "Keep your face in the frame"). */
    var note: String? = null; private set

    val watching: Boolean get() = session != null

    /** Follow the policy: one window per "on" period; the running window is dropped as soon as the policy says "off". */
    fun follow(on: Boolean) {
        if (!on) {
            startedThisWindow = false
            session?.abort(); session = null
            return
        }
        if (startedThisWindow || session != null) return
        startedThisWindow = true
        val s = FaceCheckSession(ctx, lite = true)
        val err = s.start()
        if (err != null) { alertness = Alertness.NO_FACE; note = err } else session = s
    }

    /** Call about once a second. Returns true when a window finished and [alertness] changed or was confirmed. */
    fun poll(): Boolean {
        val s = session ?: return false
        if (!s.done()) return false
        session = null
        val o = s.finish(store.load(), false)
        val f = o.fatigue
        windows++
        if (o.error != null || f == null || !f.valid) {
            alertness = Alertness.NO_FACE
            note = o.error ?: f?.reason?.prompt ?: "Keep your face in the frame"
            return true
        }
        saveFaceResults(o, false, store)
        note = null
        if (RideFitness.drowsy(f)) { alertness = Alertness.DROWSY; drowsyRecently = true; drowsyWindows++ }
        else { alertness = Alertness.ALERT; drowsyRecently = false }
        return true
    }

    fun stop() { session?.abort(); session = null; startedThisWindow = false }

    companion object {
        /** The phone's own heat reading: from MODERATE up the camera stays off so the phone can cool. */
        fun phoneHot(ctx: Context): Boolean {
            val pm = ctx.getSystemService(PowerManager::class.java) ?: return false
            return Build.VERSION.SDK_INT >= 29 && pm.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        }
    }
}
