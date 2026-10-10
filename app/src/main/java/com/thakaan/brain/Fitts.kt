package com.thakaan.brain

import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sqrt

/** One tap. [tNanos] must be MotionEvent.getEventTime (hardware time), never a UI-callback clock. */
class Tap(val tNanos: Long, val x: Double, val y: Double, val targetIndex: Int)

class TargetSpec(val x: Double, val y: Double, val widthPx: Double)

class FittsResult(
    /** Bits per second, ISO 9241-9 style with effective width. */
    val throughputBitsPerSec: Double,
    val errorRate: Double,
    val meanMovementTimeSec: Double,
    val taps: Int,
    val valid: Boolean,
    val reason: String = "",
)

/** N4: 20 s of rapid alternation between two targets. Scores are never compared across different phones. */
object Fitts {
    fun analyse(taps: List<Tap>, targets: List<TargetSpec>): FittsResult {
        if (targets.size < 2) return FittsResult(0.0, 0.0, 0.0, taps.size, false, "needs two targets")
        if (taps.size < 10) return FittsResult(0.0, 0.0, 0.0, taps.size, false, "too few taps")
        var errors = 0
        val moveTimes = ArrayList<Double>()
        val dx = ArrayList<Double>()
        val dists = ArrayList<Double>()
        for (i in taps.indices) {
            val t = taps[i]
            val tg = targets[t.targetIndex.coerceIn(0, targets.size - 1)]
            val miss = hypot(t.x - tg.x, t.y - tg.y)
            if (miss > tg.widthPx / 2) errors++
            if (i > 0) {
                val dt = (t.tNanos - taps[i - 1].tNanos) / 1e9
                if (dt in 0.05..3.0) moveTimes += dt
                val prev = taps[i - 1]
                val pt = targets[prev.targetIndex.coerceIn(0, targets.size - 1)]
                dists += hypot(tg.x - pt.x, tg.y - pt.y)
            }
            dx += miss * (if (t.x >= tg.x) 1 else -1)
        }
        if (moveTimes.size < 8) return FittsResult(0.0, errors.toDouble() / taps.size, 0.0, taps.size, false, "timestamps unusable")
        val mean = dx.average()
        val sd = sqrt(dx.sumOf { (it - mean) * (it - mean) } / (dx.size - 1))
        val we = maxOf(4.133 * sd, 1.0)
        val d = dists.average()
        val ide = ln(d / we + 1.0) / ln(2.0)
        val mt = moveTimes.average()
        return FittsResult(ide / mt, errors.toDouble() / taps.size, mt, taps.size, true)
    }

    fun toResults(r: FittsResult, tNanos: Long, hour: Double, device: String): List<BrainResult> =
        if (!r.valid) emptyList() else listOf(
            BrainResult(BrainTest.FITTS_THROUGHPUT, r.throughputBitsPerSec, tNanos, hour, device),
            BrainResult(BrainTest.FITTS_ERRORS, r.errorRate, tNanos, hour, device),
        )
}
