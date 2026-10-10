package com.thakaan.risk

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class OutlookBand(val word: String) { LOW("Low"), MODERATE("Moderate"), HIGH("High") }

/**
 * The next 20 minutes: the chance (in percent) that the strain score reaches Strained or Danger, what is pushing it (heat, wind,
 * fine dust, the trend of the score), and one thing to do about it. It can only add concern: a falling score is never read as a
 * promised recovery, missing weather counts as hot, and a worker who is already Strained or Danger is told so, not given a chance.
 */
class Outlook(
    val horizonMin: Int,
    /** 0..100; 100 when the level is already Strained or Danger. */
    val strainedPct: Int,
    val dangerPct: Int,
    val band: OutlookBand,
    val advice: String,
    val reasons: List<String>,
)

/**
 * Chance of reaching Strained within [HORIZON_MIN] minutes. The score is projected forward as its recent rising trend plus a push
 * from the heat expected over the horizon (and from fine dust when it is at least Moderate and rising), with a spread taken from
 * the engine's own score band that widens with the horizon. The push sizes are design constants chosen to lean toward warning,
 * not fitted to data; the percentages are what that model says, and the ticket for real-worker feedback is where they get tuned.
 */
object StrainOutlook {
    const val HORIZON_MIN = 20
    /** Points of score added per degree of heat-index rise expected over the horizon. */
    const val HEAT_PTS_PER_C = 3.0
    /** Added when fine dust is above 60 micrograms (worse than Satisfactory) and rising. */
    const val DUST_PTS = 2.0
    /** Added when the weather is unknown: unknown counts as hot, like everywhere else in the engine. */
    const val UNKNOWN_WEATHER_PTS = 3.0

    /** Rise of the score per minute over the last 20 minutes; zero when falling or when there is too little history. */
    fun slopePerMin(history: List<TssSample>): Double {
        val nowT = history.maxOfOrNull { it.tMin } ?: return 0.0
        val pts = history.filter { nowT - it.tMin <= 20.0 }
        if (pts.size < 4) return 0.0
        val mx = pts.sumOf { it.tMin } / pts.size; val my = pts.sumOf { it.tss.toDouble() } / pts.size
        val sxx = pts.sumOf { (it.tMin - mx) * (it.tMin - mx) }
        if (sxx < 1e-9) return 0.0
        return max(0.0, pts.sumOf { (it.tMin - mx) * (it.tss - my) } / sxx)
    }

    fun estimate(
        level: Level, tss: Int, tssLow: Int, tssHigh: Int, slopePerMin: Double,
        wbgtNow: Double?, wbgtSoon: Double?, pm25Now: Double?, pm25Soon: Double?, windMs: Double?,
    ): Outlook {
        val h = HORIZON_MIN.toDouble()
        val slope = max(0.0, slopePerMin) // no promised recovery
        val heatRise = if (wbgtNow != null && wbgtSoon != null) max(0.0, wbgtSoon - wbgtNow) else null
        val heatPush = if (heatRise != null) HEAT_PTS_PER_C * heatRise else UNKNOWN_WEATHER_PTS
        val dustRising = pm25Now != null && pm25Soon != null && pm25Soon > pm25Now && pm25Soon > 60.0
        val dustPush = if (dustRising) DUST_PTS else 0.0
        val mean = tss + slope * h + heatPush + dustPush
        val sigma = max(4.0, (tssHigh - tssLow) / 2.0 / 1.64) * sqrt(1.0 + h / 20.0)
        fun chance(threshold: Int): Int = (100.0 * (1.0 - normalCdf((threshold - mean) / sigma))).roundToInt().coerceIn(0, 100)

        val strained = if (level >= Level.STRAINED) 100 else chance(StrainForecast.STRAINED_AT)
        val danger = if (level == Level.DANGER) 100 else chance(StrainForecast.DANGER_AT)
        val band = if (strained >= 60) OutlookBand.HIGH else if (strained >= 25) OutlookBand.MODERATE else OutlookBand.LOW

        val reasons = ArrayList<String>()
        if (heatRise == null) reasons += "Weather unknown, counted as hot"
        else if (heatRise >= 1.0) reasons += "Heat index rising from %.0f to %.0f".format(wbgtNow, wbgtSoon)
        if (windMs != null && windMs < 1.5) reasons += "Little wind to cool you"
        if (dustRising) reasons += "Fine dust rising to %.0f".format(pm25Soon)
        if (slope >= 0.3) reasons += "Score rising about %.0f points every 10 min".format(slope * 10)

        val advice = when {
            level == Level.DANGER -> "Stop now. Get to shade and drink water."
            level == Level.STRAINED -> "Stop, rest in shade and drink water now."
            band == OutlookBand.HIGH -> "You may reach Strained within $HORIZON_MIN minutes. Drink water now and rest in shade for 5 minutes."
            band == OutlookBand.MODERATE -> "Strain is building. Drink water and be careful in the sun."
            else -> "You are steady. Keep drinking on schedule."
        }
        return Outlook(HORIZON_MIN, strained, danger, band, advice, reasons)
    }

    /** Standard normal CDF (Abramowitz and Stegun 7.1.26 for the error function; error below 1.5e-7). */
    internal fun normalCdf(z: Double): Double {
        val x = abs(z) / sqrt(2.0)
        val t = 1.0 / (1.0 + 0.3275911 * x)
        val erf = 1.0 - (((((1.061405429 * t - 1.453152027) * t) + 1.421413741) * t - 0.284496736) * t + 0.254829592) * t * exp(-x * x)
        return if (z >= 0) 0.5 * (1.0 + erf) else 0.5 * (1.0 - erf)
    }
}
