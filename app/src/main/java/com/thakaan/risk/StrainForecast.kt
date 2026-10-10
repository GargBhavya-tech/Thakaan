package com.thakaan.risk

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class TssSample(val tMin: Double, val tss: Int)

class Forecast(
    val tss15: Int, val tss15Low: Int, val tss15High: Int,
    val tss30: Int, val tss30Low: Int, val tss30High: Int,
    /** Minutes until the score crosses into Strained (55) or Danger (80), rounded to 5; null = not within 60 min. */
    val minutesToStrained: Int?,
    val minutesToDanger: Int?,
) {
    val headline: String? get() = minutesToStrained?.let { "Strained in about $it min" } ?: minutesToDanger?.let { "Danger in about $it min" }
}

/**
 * Strain forecast (#44): linear extrapolation of the last 20 minutes of TSS, with a band, lengthened by the planned
 * route's sun share. Fail-safe: a falling score is never extrapolated downward (no promised recovery), and the planned
 * sun share can only steepen the line.
 */
object StrainForecast {
    const val WINDOW_MIN = 20.0
    const val STRAINED_AT = 55
    const val DANGER_AT = 80

    fun project(history: List<TssSample>, plannedSunShare: Double?): Forecast? {
        val nowT = history.maxOfOrNull { it.tMin } ?: return null
        val pts = history.filter { nowT - it.tMin <= WINDOW_MIN }
        if (pts.size < 4) return null
        val n = pts.size
        val mx = pts.sumOf { it.tMin } / n
        val my = pts.sumOf { it.tss.toDouble() } / n
        val sxx = pts.sumOf { (it.tMin - mx) * (it.tMin - mx) }
        if (sxx < 1e-9) return null
        var slope = pts.sumOf { (it.tMin - mx) * (it.tss - my) } / sxx
        val intercept = my - slope * mx
        val resid = sqrt(pts.sumOf { val e = it.tss - (intercept + slope * it.tMin); e * e } / max(1, n - 2))
        slope = max(0.0, slope) // no promised recovery
        if (plannedSunShare != null && slope > 0.0) slope *= 0.8 + 0.6 * plannedSunShare.coerceIn(0.0, 1.0)
        val current = pts.maxByOrNull { it.tMin }!!.tss.toDouble()
        fun at(h: Double) = (current + slope * h)
        fun band(h: Double) = 1.64 * resid * sqrt(1.0 + h / WINDOW_MIN) + 0.1 * h
        fun clamp(x: Double) = min(100.0, max(0.0, x)).toInt()
        fun minsTo(level: Int): Int? {
            if (current >= level) return 0
            if (slope <= 1e-6) return null
            val m = (level - current) / slope
            return if (m <= 60.0) (Math.round(m / 5.0) * 5).toInt().coerceAtLeast(5) else null
        }
        return Forecast(
            clamp(at(15.0)), clamp(at(15.0) - band(15.0)), clamp(at(15.0) + band(15.0)),
            clamp(at(30.0)), clamp(at(30.0) - band(30.0)), clamp(at(30.0) + band(30.0)),
            minsTo(STRAINED_AT)?.takeIf { it > 0 }, minsTo(DANGER_AT)?.takeIf { it > 0 },
        )
    }
}

