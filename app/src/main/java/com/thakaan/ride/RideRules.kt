package com.thakaan.ride

import com.thakaan.face.FatigueSummary
import com.thakaan.risk.Level
import com.thakaan.risk.Symptom

/** Whether the front camera should be running right now, and why, in words for the riding screen. */
class CameraDecision(val on: Boolean, val reason: String)

/**
 * When the front camera watches the rider's face during a delivery. The goal is to watch while it matters and spare the phone
 * (battery and heat) the rest of the time:
 * - never when the phone is hot;
 * - never within [ARRIVE_M] of the drop point (the rider's eyes belong on the road and the door, not the check);
 * - not while stopped (a red light is too short for a reading, and nothing is at stake while still);
 * - otherwise in windows of [WINDOW_SEC] seconds (long enough for one reading) with [REST_SEC] seconds off between them, or only
 *   [REST_DROWSY_SEC] seconds off after a window that showed drowsy signs, because that is when watching matters most.
 * A window starts only after [RIDING_HOLD_SEC] seconds of steady riding. With no speed reading the camera stays off and the screen
 * says "waiting for speed": the camera only adds information (a drowsy window can raise the score, never lower it), so having it
 * off cannot lower a level, and a camera running with no sign of riding would only heat the phone.
 */
class RideCameraPolicy {
    private var isOn = false
    private var onSince = 0.0
    private var offSince = Double.NEGATIVE_INFINITY
    private var movingSince: Double? = null
    private var stoppedSince: Double? = null

    fun decide(nowSec: Double, speedMs: Double?, distToDestM: Double?, phoneHot: Boolean, drowsyRecently: Boolean): CameraDecision {
        if (phoneHot) return off(nowSec, "Camera off: phone is hot")
        if (distToDestM != null && distToDestM < ARRIVE_M) return off(nowSec, "Camera off: near the drop point")
        // no speed reading (no GPS fix yet, or the fix is stale): nothing says the rider is moving, so the camera stays off
        if (speedMs == null) { movingSince = null; stoppedSince = null; return off(nowSec, "Camera off: waiting for speed") }
        if (speedMs >= RIDING_MS) { if (movingSince == null) movingSince = nowSec; stoppedSince = null }
        else if (speedMs < STOPPED_MS) { if (stoppedSince == null) stoppedSince = nowSec; movingSince = null }
        val stopped = stoppedSince != null && nowSec - stoppedSince!! >= STOP_HOLD_SEC
        if (stopped) return off(nowSec, "Camera off: stopped")
        val riding = movingSince != null && nowSec - movingSince!! >= RIDING_HOLD_SEC

        if (isOn) {
            if (nowSec - onSince >= WINDOW_SEC) return off(nowSec, "Camera off: saving the phone's battery")
            return CameraDecision(true, "Camera on: watching while you ride")
        }
        val rest = if (drowsyRecently) REST_DROWSY_SEC else REST_SEC
        if (riding && nowSec - offSince >= rest) { isOn = true; onSince = nowSec; return CameraDecision(true, "Camera on: watching while you ride") }
        return CameraDecision(false, if (!riding) "Camera off: waiting for steady riding" else "Camera off: saving the phone's battery")
    }

    private fun off(nowSec: Double, reason: String): CameraDecision {
        if (isOn) { isOn = false; offSince = nowSec }
        return CameraDecision(false, reason)
    }

    companion object {
        const val RIDING_MS = 3.0         // about 11 km/h
        const val STOPPED_MS = 1.0
        const val STOP_HOLD_SEC = 5.0
        const val RIDING_HOLD_SEC = 8.0
        const val WINDOW_SEC = 42.0       // one face session is 3 s settle + 35 s measured
        const val REST_SEC = 80.0
        const val REST_DROWSY_SEC = 30.0
        const val ARRIVE_M = 150.0
    }
}

enum class Verdict { FIT, REST, RETRY }

/** What the pickup check decided. [restMinutes] is how long to rest when the verdict is REST. */
class Fitness(val verdict: Verdict, val headline: String, val detail: String, val restMinutes: Int = 0)

/**
 * The pickup check's decision: fit to ride, rest first, or look again. Fit needs POSITIVE evidence: a face that was read and a
 * level that is not Strained or Danger. A face that could not be read never counts as fit (the answer is to look again), and
 * anything reported as a symptom sends the rider to rest. The drowsiness limits are conservative design values (PERCLOS at or
 * above 0.15 is the usual drowsy-driver mark; two long closures or two yawns in one window), not tuned on delivery riders.
 */
object RideFitness {
    const val PERCLOS_LIMIT = 0.15
    const val LONG_CLOSURES_LIMIT = 2
    const val YAWNS_LIMIT = 2
    const val REST_MIN = 5
    const val REST_DANGER_MIN = 10

    fun drowsy(f: FatigueSummary): Boolean = f.valid && (f.perclos >= PERCLOS_LIMIT || f.longClosures >= LONG_CLOSURES_LIMIT || f.yawns >= YAWNS_LIMIT)

    fun assess(level: Level, fatigue: FatigueSummary?, symptoms: Set<Symptom>?): Fitness {
        if (level == Level.DANGER) return Fitness(Verdict.REST, "Do not ride now", "Rest in shade and drink water. Tell someone how you feel.", REST_DANGER_MIN)
        if (level == Level.STRAINED) return Fitness(Verdict.REST, "Take $REST_MIN minutes to rest", "Sit in shade and drink water, then check again.", REST_MIN)
        if (!symptoms.isNullOrEmpty()) return Fitness(Verdict.REST, "Take $REST_MIN minutes to rest", "You reported how you feel. Drink water and rest, then check again.", REST_MIN)
        if (fatigue == null || !fatigue.valid) return Fitness(Verdict.RETRY, "Look at the screen again", "The camera could not see your eyes. Hold the phone at arm's length in good light and try again.")
        if (drowsy(fatigue)) return Fitness(Verdict.REST, "Take $REST_MIN minutes to rest", "You look tired. Drink water and rest, then check again.", REST_MIN)
        return if (level == Level.WARMING) Fitness(Verdict.FIT, "Fit to ride", "Drink some water before you go.")
        else Fitness(Verdict.FIT, "Fit to ride", "You are good to go. We will watch while you ride.")
    }
}
