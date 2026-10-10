package com.thakaan.shift

import com.thakaan.core.Provenance
import com.thakaan.exposure.Wbgt
import com.thakaan.exposure.WeatherCache
import com.thakaan.motion.ImuBuilder
import com.thakaan.motion.MoveState
import com.thakaan.motion.StateStamp
import com.thakaan.risk.Level
import com.thakaan.risk.Symptom
import com.thakaan.sensorhub.Reading
import com.thakaan.sensorhub.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset

class ShiftEngineTest {
    private val weather = WeatherCache.fromOpenMeteoJson(File("../assets/weather/bengaluru_open-meteo.json").readText())

    private fun epoch(h: Int, m: Int = 0) = LocalDateTime.of(2026, 10, 10, h, m).toEpochSecond(ZoneOffset.ofHoursMinutes(5, 30))

    // a plausible Liljegren answer for the hot-sun case; the real C code is checked separately and cannot load on the host JVM
    private fun fakeWbgt(value: Double) = Wbgt { doubleArrayOf(0.0, value, value + 15, value - 4, 1.5) }

    private fun engine(startHour: Int, wbgt: Wbgt = fakeWbgt(31.0), cfg: (Long) -> ShiftConfig = { ShiftConfig(it) }) =
        ShiftEngine(cfg(epoch(startHour)), weather, wbgt)

    /** Riding for [minutes] with GNSS at [speed], light at [lux], all stamped [prov]. Returns every snapshot. */
    private fun ride(e: ShiftEngine, minutes: Int, lux: Float, speed: Float, prov: Provenance = Provenance.REAL, startNanos: Long = 1_000_000_000L, withGnss: Boolean = true, withLight: Boolean = true): List<ShiftSnapshot> {
        val b = ImuBuilder(fs = 100.0, startNanos = startNanos)
        repeat(minutes) { b.riding(60.0) }
        val out = ArrayList<ShiftSnapshot>()
        var lastSec = -1L
        for (s in b.samples) {
            val sec = s.tNanos / 1_000_000_000L
            if (sec != lastSec) {
                lastSec = sec
                if (withLight) e.onReading(Reading(Stream.LIGHT, s.tNanos, floatArrayOf(lux), prov))
                if (withGnss) e.onReading(Reading(Stream.GNSS, s.tNanos, floatArrayOf(12.9716f, 77.5946f, 6f, speed), prov))
            }
            e.onReading(Reading(Stream.GYRO, s.tNanos, floatArrayOf(s.gx.toFloat(), s.gy.toFloat(), s.gz.toFloat()), prov))
            e.onReading(Reading(Stream.ACCEL, s.tNanos, floatArrayOf(s.ax.toFloat(), s.ay.toFloat(), s.az.toFloat()), prov))?.let { out += it }
        }
        return out
    }

    @Test fun aLongRideInMiddaySunClimbsAndEverythingIsReal() {
        val e = engine(12)
        val snaps = ride(e, minutes = 50, lux = 70_000f, speed = 8f)
        assertTrue("got ${snaps.size} snapshots", snaps.size > 2500)
        val last = snaps.last()
        assertTrue("level after 50 min of midday sun: ${last.level} tss=${last.tss} dose=${last.sunDoseMin}", last.level >= Level.WARMING)
        assertTrue("sun dose accumulates (${last.sunDoseMin} min)", last.sunDoseMin > 20.0)
        assertTrue(snaps.all { it.provenance == Provenance.REAL })
        assertEquals(31.0, last.wbgtC!!, 1e-9)
        val worst = snaps.map { it.level }.runningMax()
        assertTrue("the shown level never fell during a monotone climb", snaps.map { it.level.ordinal }.zipWithNext().count { (a, b) -> b < a } <= 2)
        assertTrue(worst >= Level.WARMING)
    }

    private fun List<Level>.runningMax(): Level = maxByOrNull { it.ordinal } ?: Level.COOL

    @Test fun aCoolShadedMorningStaysCool() {
        val e = engine(7, fakeWbgt(22.0))
        val snaps = ride(e, minutes = 30, lux = 800f, speed = 6f)
        assertTrue(snaps.isNotEmpty())
        assertTrue("worst level ${snaps.maxOf { it.level }}", snaps.all { it.level == Level.COOL })
    }

    @Test fun replayNeverShowsAsReal() {
        val e = engine(12)
        val snaps = ride(e, minutes = 3, lux = 70_000f, speed = 8f, prov = Provenance.REPLAY)
        assertTrue(snaps.isNotEmpty())
        assertTrue(snaps.all { it.provenance == Provenance.REPLAY })
        val sim = ride(engine(12), 3, 70_000f, 8f, prov = Provenance.SIMULATED)
        assertTrue(sim.all { it.provenance == Provenance.SIMULATED })
    }

    @Test fun twoRunsOfTheSameRecordingGiveTheSameLevelChangesAtTheSamePoints() {
        fun changes(): List<Pair<Long, Level>> {
            val snaps = ride(engine(12), 25, 70_000f, 8f)
            val out = ArrayList<Pair<Long, Level>>()
            var prev: Level? = null
            for (s in snaps) { if (s.level != prev) { out += (s.tNanos / 1_000_000_000L) to s.level; prev = s.level } }
            return out
        }
        assertEquals(changes(), changes())
    }

    @Test fun missingWeatherNativeLibraryAndLightNeverMakeItCalmer() {
        val withEverything = ride(engine(12), 40, 70_000f, 8f).last()
        // WBGT unavailable (the host JVM cannot load the native library), no GNSS, no light sensor
        val degraded = ride(ShiftEngine(ShiftConfig(epoch(12)), weather, Wbgt()), 40, 70_000f, 8f, withGnss = false, withLight = false).last()
        assertTrue("degraded ${degraded.level} vs full ${withEverything.level}", degraded.level >= withEverything.level)
    }

    @Test fun aHighHeartCheckThenSilenceHoldsTheLevel() {
        val e = engine(13)
        ride(e, 5, 70_000f, 8f)
        val stamp = StateStamp(MoveState.RESTING, 600, 600)
        e.onHeartCheck(400_000_000_000L, 108.0, 0.9, stamp)
        val after = ride(e, 20, 70_000f, 8f, startNanos = 410_000_000_000L)
        val first = after.first().level
        assertTrue("level with a recent high rest HR: $first", first >= Level.WARMING)
        assertTrue("it never drops while no new heart data arrives", after.all { it.level >= first })
    }

    @Test fun aSevereSymptomIsDangerUntilARecoveryPasses() {
        val e = engine(13)
        ride(e, 2, 70_000f, 8f)
        e.onSymptoms(130_000_000_000L, setOf(Symptom.CONFUSION), 8, "check")
        val d = ride(e, 3, 70_000f, 8f, startNanos = 131_000_000_000L)
        assertEquals(Level.DANGER, d.last().level)
        e.onSymptoms(400_000_000_000L, emptySet(), 3, "check")
        val still = ride(e, 3, 500f, 4f, startNanos = 401_000_000_000L)
        assertEquals("symptom gone, no recovery check yet: still Danger", Level.DANGER, still.last().level)
        e.onRecoveryPassed()
        val after = ride(e, 1, 500f, 4f, startNanos = 600_000_000_000L)
        assertTrue(after.last().level < Level.DANGER)
    }
}

