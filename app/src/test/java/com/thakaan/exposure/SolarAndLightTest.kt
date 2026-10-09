package com.thakaan.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset

class SolarAndLightTest {
    private val lat = 12.9716
    private val lon = 77.5946

    /** Epoch seconds for a local India Standard Time wall-clock time (UTC+5:30). */
    private fun ist(y: Int, mo: Int, d: Int, h: Int, mi: Int): Long = LocalDateTime.of(y, mo, d, h, mi).toEpochSecond(ZoneOffset.UTC) - 19800L

    // ---------------------------------------------------------------- solar position

    @Test fun solarNoonOverBengaluruIsHighAndDueSouth() {
        val s = SolarPosition.at(ist(2026, 10, 10, 12, 7), lat, lon)
        assertEquals("elevation", 70.6, s.elevationDeg, 1.0)
        assertTrue("azimuth ${s.azimuthDeg}", s.azimuthDeg in 170.0..190.0)
    }

    @Test fun sunIsDownAtMidnightAndUpAfterSunrise() {
        assertTrue(SolarPosition.at(ist(2026, 10, 10, 0, 0), lat, lon).elevationDeg < -30)
        assertTrue(SolarPosition.at(ist(2026, 10, 10, 5, 30), lat, lon).elevationDeg < 0)
        assertTrue(SolarPosition.at(ist(2026, 10, 10, 6, 40), lat, lon).elevationDeg > 0)
    }

    @Test fun morningSunIsInTheEastAndAfternoonSunInTheWest() {
        assertTrue(SolarPosition.at(ist(2026, 10, 10, 9, 0), lat, lon).azimuthDeg in 60.0..120.0)
        assertTrue(SolarPosition.at(ist(2026, 10, 10, 15, 30), lat, lon).azimuthDeg in 240.0..300.0)
    }

    @Test fun clearSkyAndCloudModels() {
        assertEquals(960.0, SolarPosition.clearSkyGhi(70.0), 60.0)
        assertEquals(0.0, SolarPosition.clearSkyGhi(-5.0), 0.0)
        assertEquals(1.0, SolarPosition.cloudFactor(null), 0.0)
        assertEquals(0.25, SolarPosition.cloudFactor(100.0), 1e-9)
        assertTrue(SolarPosition.cloudFactor(50.0) in 0.9..1.0)
    }

    // ---------------------------------------------------------------- E1 sun / shade

    private val noon = ist(2026, 10, 10, 12, 7)
    private val flat = doubleArrayOf(0.0, 0.0, 9.81)
    private val upright = doubleArrayOf(0.0, 9.81, 0.0)

    private fun input(
        lux: Double?, cct: Double? = null, epoch: Long = noon, gravity: DoubleArray? = flat, heading: Double? = null,
        cloud: Double? = null, skyBlocked: Boolean? = null,
    ) = LightInput(0L, epoch, lat, lon, lux, cct, gravity, heading, 5.0, cloud, skyBlocked)

    @Test fun brightSunIsSun() {
        assertEquals(SunState.SUN, LightClassifier.classifyInstant(input(55_000.0)).state)
        assertEquals(SunState.SUN, LightClassifier.classifyInstant(input(66_110.0)).state) // saturated sensor
    }

    @Test fun openShadeIsShade() {
        val e = LightClassifier.classifyInstant(input(9_000.0, cct = 8_500.0))
        assertEquals(SunState.SHADE, e.state)
        assertFalse(e.effectiveSun)
    }

    @Test fun heavyCloudIsOvercast() {
        assertEquals(SunState.OVERCAST, LightClassifier.classifyInstant(input(20_000.0, cct = 6_800.0, cloud = 90.0)).state)
    }

    @Test fun nightIsDark() {
        assertEquals(SunState.DARK, LightClassifier.classifyInstant(input(5.0, epoch = ist(2026, 10, 10, 22, 0))).state)
    }

    @Test fun noLightReadingCountsAsSun() {
        val e = LightClassifier.classifyInstant(input(null))
        assertEquals(SunState.UNKNOWN, e.state)
        assertTrue("unknown shade must be treated as sun", e.effectiveSun)
    }

    @Test fun lowSunBehindTheRiderIsNotBelievedAsShade() {
        // 16:30 IST: sun low in the west (az ~275). Riding east (heading 90) the phone faces the rider and the sun is behind them.
        val epoch = ist(2026, 10, 10, 16, 30)
        val sun = SolarPosition.at(epoch, lat, lon)
        assertTrue("sun should be low, got ${sun.elevationDeg}", sun.elevationDeg in 10.0..40.0)
        val e = LightClassifier.classifyInstant(input(lux = 2_500.0, epoch = epoch, gravity = upright, heading = 90.0))
        assertTrue("sensor must be flagged untrusted: ${e.reason}", !e.sensorTrusted)
        assertTrue("rider is baking in the sun: ${e.reason}", e.effectiveSun)
    }

    @Test fun skyViewCanOverrideTheBehindRiderFailSafe() {
        val epoch = ist(2026, 10, 10, 16, 30)
        val e = LightClassifier.classifyInstant(input(lux = 2_500.0, epoch = epoch, gravity = upright, heading = 90.0, skyBlocked = true))
        assertEquals(SunState.SHADE, e.state)
        assertFalse(e.effectiveSun)
    }

    @Test fun losingInformationNeverLowersTheExposure() {
        // For a spread of readings: removing any input must not turn "in sun" into "in shade".
        val epochs = listOf(ist(2026, 10, 10, 9, 0), noon, ist(2026, 10, 10, 15, 0), ist(2026, 10, 10, 16, 30))
        for (epoch in epochs) for (lux in listOf(500.0, 3_000.0, 12_000.0, 30_000.0, 55_000.0)) {
            for (g in listOf(flat, upright)) {
                val full = LightClassifier.classifyInstant(input(lux, 5_600.0, epoch, g, 90.0, 20.0, null))
                val degraded = listOf(
                    input(null, 5_600.0, epoch, g, 90.0, 20.0, null),   // light sensor blocked
                    input(lux, null, epoch, g, 90.0, 20.0, null),       // no colour temperature
                    input(lux, 5_600.0, epoch, null, 90.0, 20.0, null), // no tilt
                    input(lux, 5_600.0, epoch, g, null, 20.0, null),    // GNSS lost: no heading
                )
                for (d in degraded) {
                    val e = LightClassifier.classifyInstant(d)
                    if (full.effectiveSun) assertTrue("lux=$lux epoch=$epoch lost an input and dropped to shade: ${e.reason}", e.effectiveSun)
                }
            }
        }
    }

    @Test fun trackerNoticesSunFastAndShadeSlowly() {
        val t = LightTracker()
        val sun = input(55_000.0)
        val shade = input(5_000.0, cct = 8_500.0)
        repeat(3) { t.update(sun) }
        assertTrue(t.current!!.effectiveSun)
        repeat(4) { t.update(shade) }
        assertTrue("4 s of shade is not yet enough", t.current!!.effectiveSun)
        t.update(shade)
        assertFalse("5 s of shade flips it", t.current!!.effectiveSun)
        t.update(sun)
        assertFalse("one second of sun is not yet enough", t.current!!.effectiveSun)
        t.update(sun)
        assertTrue("2 s of sun flips it", t.current!!.effectiveSun)
        assertNotNull(t.current)
    }
}
