package com.thakaan.exposure

import com.thakaan.core.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

class IrradianceAndWeatherTest {
    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = LocalDateTime.of(y, mo, d, h, mi).toEpochSecond(ZoneOffset.UTC) - 19800L

    private val noon = ist(2026, 10, 10, 12, 7)
    private val sunNoon = SolarPosition.at(noon, 12.9716, 77.5946)

    private fun est(state: SunState, sun: Boolean, cos: Double? = 0.94, trusted: Boolean = true) =
        LightEstimate(state, sun, 0.8, "test", cos, trusted, Provenance.REAL)

    // ---------------------------------------------------------------- irradiance (E15)

    @Test fun luxIsConvertedAt110LumensPerWatt() {
        val e = Irradiance.estimate(44_000.0, sunNoon, est(SunState.SUN, true, cos = sunNoon.sinElevation), null)
        assertEquals(IrradianceMethod.SENSOR, e.method)
        assertEquals(400.0, e.wm2, 5.0) // flat sensor, no tilt gain
    }

    @Test fun saturationSwitchesToTheClearSkyModel() {
        val e = Irradiance.estimate(66_110.0, sunNoon, est(SunState.SUN, true), null)
        assertTrue(e.saturated)
        assertEquals(IrradianceMethod.CLEAR_SKY_MODEL, e.method)
        assertTrue("plausible noon value, got ${e.wm2}", e.wm2 in 800.0..1100.0)
    }

    @Test fun cloudLowersTheModelValue() {
        val clear = Irradiance.estimate(66_110.0, sunNoon, est(SunState.SUN, true), 0.0).wm2
        val cloudy = Irradiance.estimate(66_110.0, sunNoon, est(SunState.SUN, true), 90.0).wm2
        assertTrue(cloudy < clear * 0.6)
    }

    @Test fun shadeReadsLowAndNightReadsZero() {
        val shade = Irradiance.estimate(5_000.0, sunNoon, est(SunState.SHADE, false, cos = sunNoon.sinElevation), null)
        assertEquals(45.0, shade.wm2, 5.0)
        val night = SolarPosition.at(ist(2026, 10, 10, 23, 0), 12.9716, 77.5946)
        assertEquals(0.0, Irradiance.estimate(3.0, night, est(SunState.DARK, false), null).wm2, 0.0)
    }

    @Test fun anUntrustedSensorInSunNeverReportsLessThanTheModel() {
        // sun behind the rider: the sensor reads a few thousand lux but the rider is in sun
        val e = Irradiance.estimate(2_500.0, sunNoon, est(SunState.SUN, true, cos = 0.1, trusted = false), null)
        assertTrue("got ${e.wm2}", e.wm2 > 700.0)
    }

    @Test fun missingLuxFallsBackToTheModel() {
        val e = Irradiance.estimate(null, sunNoon, est(SunState.UNKNOWN, true, cos = null, trusted = false), null)
        assertEquals(IrradianceMethod.CLEAR_SKY_MODEL, e.method)
        assertTrue(e.wm2 > 700.0)
    }

    // ---------------------------------------------------------------- sun dose (E2)

    @Test fun sunDoseCountsOnlyTimeInSun() {
        val d = SunDose()
        repeat(600) { d.update(1.0, 800.0, true, sunNoon, Provenance.REAL) }
        repeat(600) { d.update(1.0, 50.0, false, sunNoon, Provenance.REAL) }
        assertEquals(10.0, d.minutesInSun, 1e-9)
        assertEquals(800.0 * 600 / 3600.0, d.energyWhPerM2, 1e-6)
        assertEquals(Provenance.REAL, d.provenance)
    }

    @Test fun lowSunCountsForLessThanNoonSun() {
        val low = SolarPosition.at(ist(2026, 10, 10, 7, 0), 12.9716, 77.5946)
        val a = SunDose().also { d -> repeat(600) { d.update(1.0, 300.0, true, low, Provenance.REAL) } }
        val b = SunDose().also { d -> repeat(600) { d.update(1.0, 900.0, true, sunNoon, Provenance.REAL) } }
        assertTrue(a.weightedMinutes < b.weightedMinutes * 0.7)
        assertEquals(a.minutesInSun, b.minutesInSun, 1e-9)
    }

    @Test fun replayedInputsMakeTheDoseReplay() {
        val d = SunDose()
        d.update(1.0, 500.0, true, sunNoon, Provenance.REPLAY)
        d.update(1.0, 500.0, true, sunNoon, Provenance.REAL)
        assertEquals(Provenance.REPLAY, d.provenance)
    }

    // ---------------------------------------------------------------- weather cache (E9)

    private val json by lazy { File("../assets/weather/bengaluru_open-meteo.json").readText() }

    @Test fun cacheLoadsTheStagedForecastAndAnswersOffline() {
        val c = WeatherCache.fromOpenMeteoJson(json)
        assertEquals(144, c.hours)
        val w = c.at(ist(2026, 10, 10, 14, 0))
        assertNotNull(w)
        assertTrue("temp ${w!!.tempC}", w.tempC in 18.0..40.0)
        assertTrue(w.relativeHumidityPct in 10.0..100.0)
        assertEquals("cache", w.source)
        assertEquals(Provenance.REAL, w.provenance)
    }

    @Test fun interpolatesBetweenHours() {
        val c = WeatherCache.fromOpenMeteoJson(json)
        val a = c.at(ist(2026, 10, 10, 14, 0))!!.tempC
        val b = c.at(ist(2026, 10, 10, 15, 0))!!.tempC
        val mid = c.at(ist(2026, 10, 10, 14, 30))!!.tempC
        assertEquals((a + b) / 2, mid, 0.01)
    }

    @Test fun refusesToGuessFarOutsideTheCache() {
        val c = WeatherCache.fromOpenMeteoJson(json)
        assertNull(c.at(ist(2026, 12, 1, 12, 0)))
        assertNull(c.at(ist(2026, 6, 1, 12, 0)))
    }

    @Test fun phoneNudgeIsSmallAndOnlyWhenIdleAndUncased() {
        val forecast = 31.0
        assertEquals(forecast, PhoneAirTemperature.nudge(forecast, 45.0, false, false, null, idle = false, uncased = true), 0.0)
        assertEquals(forecast, PhoneAirTemperature.nudge(forecast, 45.0, false, false, null, idle = true, uncased = false), 0.0)
        val nudged = PhoneAirTemperature.nudge(forecast, 50.0, false, false, null, idle = true, uncased = true)
        assertTrue("nudge must stay within 2 C, got ${nudged - forecast}", nudged - forecast in -2.0..2.0)
        assertFalse(nudged == forecast)
        // self-heating from screen and charging is subtracted, so a hot-because-charging phone is not read as a hot day
        val charging = PhoneAirTemperature.nudge(forecast, 42.0, true, true, 0.8, idle = true, uncased = true)
        val cool = PhoneAirTemperature.nudge(forecast, 42.0, false, false, 0.2, idle = true, uncased = true)
        assertTrue(charging < cool)
    }
}
