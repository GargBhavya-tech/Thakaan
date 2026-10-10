package com.thakaan.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AirDoseTest {
    private val json = """{"utc_offset_seconds":19800,"hourly":{"time":["2026-10-10T10:00","2026-10-10T11:00","2026-10-10T12:00"],"pm2_5":[20.0,30.0,null]}}"""
    private fun epoch(h: Int) = java.time.LocalDateTime.of(2026, 10, 10, h, 0).toEpochSecond(java.time.ZoneOffset.UTC) - 19800

    @Test fun readsHourlyValuesAndInterpolates() {
        val c = AirQualityCache.fromOpenMeteoJson(json)
        assertEquals(20.0, c.pm25At(epoch(10))!!, 1e-9)
        assertEquals(25.0, c.pm25At(epoch(10) + 1800)!!, 1e-9)
    }

    @Test fun farOutsideTheCacheIsNoDataNotCleanAir() {
        val c = AirQualityCache.fromOpenMeteoJson(json)
        assertNull(c.pm25At(epoch(10) - 3 * 86400))
        assertNull(c.pm25At(epoch(12) + 3 * 86400))
    }

    @Test fun aMissingHourFallsBackToItsNeighbourNeverToZero() {
        val c = AirQualityCache.fromOpenMeteoJson(json)
        assertEquals(30.0, c.pm25At(epoch(11) + 1800)!!, 1e-9)
    }

    @Test fun greenwaldGivesPlausibleVentilation() {
        val rest = Ventilation.fromHeartRate(70.0, 30)
        val work = Ventilation.fromHeartRate(120.0, 30)
        assertTrue("rest $rest", rest in 6.0..14.0)
        assertTrue("work $work", work in 18.0..40.0)
        assertTrue(work > 2 * rest)
    }

    @Test fun olderAgeAndAHigherHeartRateBothRaiseVentilation() {
        assertTrue(Ventilation.fromHeartRate(90.0, 55) > Ventilation.fromHeartRate(90.0, 25))
        assertTrue(Ventilation.fromHeartRate(110.0, 30) > Ventilation.fromHeartRate(90.0, 30))
    }

    @Test fun workloadEstimateRisesWithMetsAndNeverGoesBelowRest() {
        assertEquals(7.0, Ventilation.fromMets(0.3), 1e-9)
        assertTrue(Ventilation.fromMets(3.5) > Ventilation.fromMets(1.5))
    }

    @Test fun doseIsConcentrationTimesVentilationTimesTime() {
        val d = AirDoseLedger()
        repeat(60) { d.addMinute(30.0, 10.0, false) } // 30 ug/m3 x 10 L/min for an hour = 30 x 0.6 m3 = 18 ug
        assertEquals(18.0, d.doseUg, 1e-9)
        assertEquals(30.0, d.meanConcentration!!, 1e-9)
    }

    @Test fun minutesWithoutAirDataAddNothingAndAreCounted() {
        val d = AirDoseLedger()
        d.addMinute(null, 12.0, true)
        assertEquals(0.0, d.doseUg, 1e-9)
        assertEquals(1, d.minutesMissing)
        assertTrue(d.summary().contains("No air data"))
    }

    @Test fun theSummaryLabelsTheValueAsModelledAndSaysHowMuchWasEstimated() {
        val d = AirDoseLedger()
        repeat(10) { d.addMinute(24.0, 15.0, false) }
        val s = d.summary()
        assertTrue(s, s.contains("modelled") && s.contains("estimated from workload"))
    }

    @Test fun theStagedFileLoadsAndCoversTheDemoDays() {
        val f = File("src/main/assets/airquality/bengaluru_open-meteo_pm25.json")
        if (!f.exists()) return
        val c = AirQualityCache.fromOpenMeteoJson(f.readText())
        val mid = java.time.LocalDateTime.of(2026, 10, 10, 14, 30).toEpochSecond(java.time.ZoneOffset.UTC) - 19800
        assertNotNull(c.pm25At(mid))
        assertTrue(c.pm25At(mid)!! in 2.0..200.0)
    }
}
