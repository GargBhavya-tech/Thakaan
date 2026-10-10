package com.thakaan.exposure

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlin.math.exp
import kotlin.math.pow

/**
 * Hourly PM2.5 (micrograms per cubic metre) for the area, staged by ticket #4 in assets/airquality and read offline. It is a
 * modelled value (Open-Meteo / Copernicus CAMS, CC BY 4.0), NOT a station reading and NOT the worker's own air, and the card
 * says so. Returns null far outside the cache, and the dose card then says "no air data" instead of inventing a number.
 */
class AirQualityCache private constructor(private val t: LongArray, private val pm: DoubleArray) {
    fun pm25At(epochSec: Long, maxExtrapolateSec: Long = 6 * 3600): Double? {
        if (t.isEmpty()) return null
        if (epochSec < t.first() - maxExtrapolateSec || epochSec > t.last() + maxExtrapolateSec) return null
        if (epochSec <= t.first()) return pm.first().takeUnless { it.isNaN() }
        if (epochSec >= t.last()) return pm.last().takeUnless { it.isNaN() }
        var hi = t.indexOfFirst { it >= epochSec }
        if (hi <= 0) hi = 1
        val lo = hi - 1
        val a = pm[lo]; val b = pm[hi]
        if (a.isNaN() && b.isNaN()) return null
        if (a.isNaN()) return b
        if (b.isNaN()) return a
        return a + (b - a) * (epochSec - t[lo]).toDouble() / (t[hi] - t[lo])
    }

    companion object {
        fun fromOpenMeteoJson(json: String): AirQualityCache {
            val root = JSONObject(json)
            val offset = root.optInt("utc_offset_seconds", 0)
            val h = root.getJSONObject("hourly")
            val times = h.getJSONArray("time"); val v = h.getJSONArray("pm2_5")
            val epochs = LongArray(times.length()) { LocalDateTime.parse(times.getString(it)).toEpochSecond(ZoneOffset.UTC) - offset }
            return AirQualityCache(epochs, DoubleArray(v.length()) { if (v.isNull(it)) Double.NaN else v.getDouble(it) })
        }
    }
}

/** How much air the worker breathes. */
object Ventilation {
    /** Typical forced vital capacity (litres) used until the worker's own or an Indian reference value is entered; shown as typical. */
    const val TYPICAL_FVC_L = 3.3

    /**
     * Greenwald et al. 2019 (PLOS ONE 14(7): e0218673), heart-rate-only model D1, fitted on pooled data: VE = exp(-9.59) x HR^2.39 x
     * age^0.274 x sex^-0.204 x FVC^0.520, with HR in beats a minute, age in years, sex 1 = male and 2 = female, FVC in litres,
     * VE in litres a minute. Median cross-validated error about 0%, but the middle half of single estimates spans 45%, so it
     * only averages out over a shift. [sexCode] 1.5 when the worker's sex is not recorded.
     */
    fun fromHeartRate(hrBpm: Double, ageYears: Int, fvcL: Double = TYPICAL_FVC_L, sexCode: Double = 1.5): Double =
        exp(-9.59) * hrBpm.pow(2.39) * ageYears.coerceIn(10, 90).toDouble().pow(0.274) * sexCode.pow(-0.204) * fvcL.pow(0.520)

    /**
     * Between heart-rate checks only the workload is known, so ventilation is estimated from the metabolic rate (METs):
     * about 7 L/min at rest, rising roughly 4 L/min for each MET above 1 (walking at 3.5 METs is about 17 L/min). Labelled
     * as an estimate. Never below the resting value.
     */
    fun fromMets(mets: Double): Double = 7.0 + 4.0 * (mets.coerceIn(1.0, 12.0) - 1.0)
}

/**
 * Air dose today (#58): the sum over each minute of PM2.5 concentration x minute ventilation x time, in micrograms of PM2.5
 * breathed in. Heart-rate minutes and workload-estimated minutes are counted separately so the card can say how much of the
 * dose is an estimate. Minutes without air data add nothing and are counted as missing, never as clean air.
 */
class AirDoseLedger {
    var doseUg = 0.0; private set
    var minutesCounted = 0; private set
    var minutesMissing = 0; private set
    var minutesFromHr = 0; private set
    private var concSum = 0.0

    fun addMinute(pm25UgM3: Double?, veLPerMin: Double, fromHeartRate: Boolean) {
        if (pm25UgM3 == null) { minutesMissing++; return }
        doseUg += pm25UgM3 * veLPerMin / 1000.0 // litres to cubic metres, one minute
        concSum += pm25UgM3
        minutesCounted++
        if (fromHeartRate) minutesFromHr++
    }

    val meanConcentration: Double? get() = if (minutesCounted == 0) null else concSum / minutesCounted

    /** Text for the Home card and the shift report. */
    fun summary(): String {
        if (minutesCounted == 0) return if (minutesMissing > 0) "No air data for this area right now" else "Just started"
        val est = if (minutesFromHr * 2 >= minutesCounted) "mostly from heart rate" else "mostly estimated from workload"
        return "%.0f µg of fine dust breathed in (area average %.0f µg/m³, modelled; %s)".format(doseUg, meanConcentration, est)
    }
}
