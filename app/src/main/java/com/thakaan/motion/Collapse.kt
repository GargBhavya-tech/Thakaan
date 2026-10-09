package com.thakaan.motion

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

enum class CollapseKind { FALL, TIP_OVER, SLUMP }

class CollapseEvent(val tNanos: Long, val kind: CollapseKind, val impactAtNanos: Long)

/**
 * Starting thresholds, to be tuned with real falls onto a mat and real sit-downs (ticket #15 test).
 * Heat collapse is often a slump, not a fall: no spike, just a body folding, then stillness.
 */
data class CollapseRules(
    val freeFallMs2: Double = 3.0,
    val freeFallMinMs: Long = 80,
    val impactMs2: Double = 25.0,
    val tipOverImpactMs2: Double = 20.0,
    val orientationChangeDeg: Double = 45.0,
    val orientationWindowSec: Double = 6.0,
    val stillStd: Double = 0.03,     // lying unconscious: essentially no micro-movement
    val stillSeconds: Double = 8.0,
    val moveStd: Double = 0.08,      // fidgeting, breathing against a pocket, getting up
)

/**
 * Detects a fall, a bike tip-over or a slump followed by stillness. Emits ONE event after the stillness has been
 * confirmed; the event only starts the spoken countdown ([CountdownMachine]), it never raises the alarm by itself.
 * Feed accelerometer samples in time order.
 */
class CollapseDetector(private val rules: CollapseRules = CollapseRules()) {
    private class G(val t: Long, val x: Double, val y: Double, val z: Double)

    private var gx = 0.0; private var gy = 0.0; private var gz = 0.0
    private var haveG = false
    private val gHistory = ArrayDeque<G>() // slow gravity direction, one entry about every 250 ms
    private var lastG = 0L

    private var freeFallStart: Long? = null
    private var ridingContext = false
    private var movingBefore = false

    private enum class S { WATCH, CONFIRM }
    private var s = S.WATCH
    private var candKind: CollapseKind? = null
    private var candImpact = 0L
    private var calmSince: Long? = null
    private var devSum = 0.0
    private var devSumSq = 0.0
    private var devN = 0
    private var winStart = 0L
    private var lastMagT = 0L

    /** Tell the detector the rider is on the bike (from [Activity.RIDING]); a tip-over needs that context. */
    fun setRiding(riding: Boolean) { ridingContext = riding }

    /** True while the phone is known to be lying on a desk or table: a phone put down is not a person going down. */
    var suppressed: Boolean = false

    fun onAccel(tNanos: Long, ax: Double, ay: Double, az: Double): CollapseEvent? {
        val mag = sqrt(ax * ax + ay * ay + az * az)

        // slow gravity estimate (time constant about 1 s)
        val dt = if (lastMagT == 0L) 0.01 else ((tNanos - lastMagT) / 1e9).coerceIn(0.0005, 0.1)
        lastMagT = tNanos
        val alpha = (dt / 1.0).coerceAtMost(1.0)
        if (!haveG) { gx = ax; gy = ay; gz = az; haveG = true } else { gx += alpha * (ax - gx); gy += alpha * (ay - gy); gz += alpha * (az - gz) }
        if (tNanos - lastG >= 250_000_000L) { gHistory.addLast(G(tNanos, gx, gy, gz)); lastG = tNanos }
        while (gHistory.isNotEmpty() && gHistory.first().t < tNanos - (rules.orientationWindowSec * 1e9).toLong() - 1_000_000_000L) gHistory.removeFirst()

        when (s) {
            S.WATCH -> {
                if (suppressed) return null
                // free fall followed by impact
                if (mag < rules.freeFallMs2) { if (freeFallStart == null) freeFallStart = tNanos } else {
                    val ff = freeFallStart
                    if (ff != null && (tNanos - ff) / 1_000_000 >= rules.freeFallMinMs && mag > rules.impactMs2 * 0.5) beginConfirm(if (ridingContext) CollapseKind.TIP_OVER else CollapseKind.FALL, tNanos)
                    if (ff != null && mag >= rules.freeFallMs2) freeFallStart = null
                }
                if (s == S.WATCH && mag > rules.impactMs2) beginConfirm(if (ridingContext) CollapseKind.TIP_OVER else CollapseKind.FALL, tNanos)
                else if (s == S.WATCH && ridingContext && mag > rules.tipOverImpactMs2 && orientationChange(tNanos) > rules.orientationChangeDeg * 0.6) beginConfirm(CollapseKind.TIP_OVER, tNanos)

                // slump: a large, no-impact change of orientation within a few seconds
                if (s == S.WATCH && gHistory.size > 8) {
                    val change = orientationChange(tNanos)
                    if (change >= rules.orientationChangeDeg) beginConfirm(CollapseKind.SLUMP, tNanos)
                }
                return null
            }
            S.CONFIRM -> {
                // After the event, the person must stay essentially motionless for stillSeconds. Any real movement cancels.
                val dev = mag - 9.81
                if (winStart == 0L) winStart = tNanos
                devSum += dev; devSumSq += dev * dev; devN++
                if ((tNanos - winStart) >= 1_000_000_000L) {
                    val mean = devSum / devN
                    val std = sqrt((devSumSq / devN - mean * mean).coerceAtLeast(0.0))
                    devSum = 0.0; devSumSq = 0.0; devN = 0; winStart = tNanos
                    if (tNanos - candImpact < 2_000_000_000L) {
                        // let the impact settle first
                    } else if (std > rules.moveStd) {
                        reset() // got up, fidgeted, or is being moved: not a collapse
                    } else if (std <= rules.stillStd) {
                        if (calmSince == null) calmSince = tNanos
                        if ((tNanos - calmSince!!) / 1e9 >= rules.stillSeconds) {
                            val ev = CollapseEvent(tNanos, candKind!!, candImpact)
                            reset()
                            return ev
                        }
                    } else {
                        calmSince = null // in between: not yet calm
                    }
                }
                return null
            }
        }
    }

    private fun beginConfirm(kind: CollapseKind, t: Long) {
        s = S.CONFIRM; candKind = kind; candImpact = t; calmSince = null
        devSum = 0.0; devSumSq = 0.0; devN = 0; winStart = 0L
    }

    private fun reset() {
        s = S.WATCH; candKind = null; calmSince = null; freeFallStart = null
        devSum = 0.0; devSumSq = 0.0; devN = 0; winStart = 0L
        gHistory.clear(); lastG = 0L
    }

    /** Largest angle between the current gravity direction and any slow-gravity sample inside the orientation window. */
    private fun orientationChange(now: Long): Double {
        val len = sqrt(gx * gx + gy * gy + gz * gz)
        if (len < 1e-6) return 0.0
        var best = 0.0
        val from = now - (rules.orientationWindowSec * 1e9).toLong()
        for (h in gHistory) {
            if (h.t < from) continue
            val hl = sqrt(h.x * h.x + h.y * h.y + h.z * h.z)
            if (hl < 1e-6) continue
            val c = ((gx * h.x + gy * h.y + gz * h.z) / (len * hl)).coerceIn(-1.0, 1.0)
            best = maxOf(best, acos(c) * 180.0 / PI)
        }
        return best
    }
}

enum class CountdownState { IDLE, COUNTDOWN, ALARM }

/**
 * The countdown ALWAYS runs first: ALARM is reachable only from COUNTDOWN after it times out. A voice "haan" or a tap
 * cancels it. Without this, sitting down on a kerb would set off a siren.
 */
class CountdownMachine(private val countdownMs: Long = 15_000L) {
    var state: CountdownState = CountdownState.IDLE
        private set
    private var startedAt = 0L
    var trigger: CollapseKind? = null
        private set

    fun start(nowMs: Long, kind: CollapseKind) {
        if (state != CountdownState.IDLE) return
        state = CountdownState.COUNTDOWN; startedAt = nowMs; trigger = kind
    }

    /** The worker said "haan" or tapped "I'm OK". */
    fun confirmSafe() {
        if (state == CountdownState.COUNTDOWN) { state = CountdownState.IDLE; trigger = null }
    }

    /** Call at least once a second. Returns true exactly when the alarm starts. */
    fun tick(nowMs: Long): Boolean {
        if (state == CountdownState.COUNTDOWN && nowMs - startedAt >= countdownMs) { state = CountdownState.ALARM; return true }
        return false
    }

    fun secondsLeft(nowMs: Long): Int = if (state == CountdownState.COUNTDOWN) ((countdownMs - (nowMs - startedAt) + 999) / 1000).toInt().coerceAtLeast(0) else 0

    /** Someone has taken charge of the alarm (bystander screen dismissed, contact confirmed). */
    fun resolve() { state = CountdownState.IDLE; trigger = null }
}

/** The text a bystander screen offers to send. It is a PREVIEW: nothing is sent without the worker or a bystander tapping send. */
class SmsPreview(val toNumbers: List<String>, val body: String) {
    companion object {
        fun of(workerName: String, kind: CollapseKind, lat: Double?, lon: Double?, contacts: List<String>): SmsPreview {
            val where = if (lat != null && lon != null) "https://maps.google.com/?q=%.5f,%.5f".format(lat, lon) else "location unknown"
            val what = when (kind) { CollapseKind.FALL -> "fell"; CollapseKind.TIP_OVER -> "may have fallen off the bike"; CollapseKind.SLUMP -> "may have collapsed from heat" }
            return SmsPreview(contacts, "Thakaan alert: $workerName $what and has not responded. Please check now. $where")
        }
    }
}
