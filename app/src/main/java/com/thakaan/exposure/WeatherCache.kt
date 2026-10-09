package com.thakaan.exposure

import com.thakaan.core.Provenance
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneOffset

class Weather(
    val tempC: Double,
    val relativeHumidityPct: Double,
    val windMs: Double,
    val pressureHpa: Double,
    val cloudPct: Double,
    /** Global horizontal shortwave radiation (W/m^2) from the forecast, a cross-check for E15. */
    val ghiWm2: Double,
    /** Where the number came from: always the on-device cache; never a live call on the demo path. */
    val source: String = "cache",
    /** A cached forecast is a real source, but not a live measurement. */
    val provenance: Provenance = Provenance.REAL,
)

/**
 * E9: the Open-Meteo hourly grid staged by ticket #4 (assets/weather), interpolated offline. Returns null when the time
 * is far outside the cache, and callers must then assume hot (fail safe) rather than cool.
 */
class WeatherCache private constructor(private val t: LongArray, private val cols: Map<String, DoubleArray>) {
    val firstEpoch: Long get() = t.first()
    val lastEpoch: Long get() = t.last()
    val hours: Int get() = t.size

    fun at(epochSec: Long, maxExtrapolateSec: Long = 3 * 3600): Weather? {
        if (t.isEmpty()) return null
        if (epochSec < t.first() - maxExtrapolateSec || epochSec > t.last() + maxExtrapolateSec) return null
        fun interp(name: String): Double {
            val v = cols[name] ?: return Double.NaN
            if (epochSec <= t.first()) return v.first()
            if (epochSec >= t.last()) return v.last()
            var hi = t.indexOfFirst { it >= epochSec }
            if (hi <= 0) hi = 1
            val lo = hi - 1
            val f = (epochSec - t[lo]).toDouble() / (t[hi] - t[lo])
            return v[lo] + (v[hi] - v[lo]) * f
        }
        val temp = interp("temperature_2m")
        if (temp.isNaN()) return null
        return Weather(
            tempC = temp,
            relativeHumidityPct = interp("relative_humidity_2m").orDefault(50.0),
            windMs = interp("wind_speed_10m").orDefault(0.0),
            pressureHpa = interp("surface_pressure").orDefault(1013.0),
            cloudPct = interp("cloud_cover").orDefault(0.0),
            ghiWm2 = interp("shortwave_radiation").orDefault(0.0),
        )
    }

    private fun Double.orDefault(d: Double) = if (isNaN()) d else this

    companion object {
        private val columns = listOf("temperature_2m", "relative_humidity_2m", "wind_speed_10m", "surface_pressure", "cloud_cover", "shortwave_radiation")

        fun fromOpenMeteoJson(json: String): WeatherCache {
            val root = JSONObject(json)
            val offset = root.optInt("utc_offset_seconds", 0)
            val h = root.getJSONObject("hourly")
            val times = h.getJSONArray("time")
            val epochs = LongArray(times.length()) {
                LocalDateTime.parse(times.getString(it)).toEpochSecond(ZoneOffset.UTC) - offset
            }
            val cols = HashMap<String, DoubleArray>()
            for (c in columns) {
                if (!h.has(c)) continue
                val a = h.getJSONArray(c)
                cols[c] = DoubleArray(a.length()) { if (a.isNull(it)) Double.NaN else a.getDouble(it) }
            }
            return WeatherCache(epochs, cols)
        }
    }
}

/**
 * Local nudge to the forecast from the phone's battery temperature (E3/E16). Only when the phone has been idle and is
 * uncased, after subtracting its own self-heating. A mounted phone in a case reads hot, so the forecast is the base and
 * the phone only nudges it, by at most +/- [maxNudgeC].
 */
object PhoneAirTemperature {
    fun nudge(
        forecastC: Double,
        batteryC: Double,
        screenOn: Boolean,
        charging: Boolean,
        thermalHeadroom: Double?,
        idle: Boolean,
        uncased: Boolean,
        maxNudgeC: Double = 2.0,
        weight: Double = 0.25,
    ): Double {
        if (!idle || !uncased) return forecastC
        var selfHeat = 0.0
        if (screenOn) selfHeat += 3.0
        if (charging) selfHeat += 4.0
        if (thermalHeadroom != null && thermalHeadroom > 0.5) selfHeat += (thermalHeadroom - 0.5) * 10.0 // compute heat shows up as headroom use
        val phoneEstimate = batteryC - 6.0 - selfHeat // the case-less phone sits a few degrees above the air
        val delta = ((phoneEstimate - forecastC) * weight).coerceIn(-maxNudgeC, maxNudgeC)
        return forecastC + delta
    }
}
