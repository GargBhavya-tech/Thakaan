package com.thakaan.shift

import com.thakaan.core.Provenance
import com.thakaan.risk.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShiftLogTest {
    private val sec = 1_000_000_000L

    @Test fun timeIsCreditedToTheLevelShowingWhenTheIntervalBegan() {
        val log = ShiftLog()
        log.add(0, Level.COOL, 10, Provenance.REAL)
        log.add(60 * sec, Level.WARMING, 40, Provenance.REAL)   // the first minute was Cool
        log.add(90 * sec, Level.WARMING, 45, Provenance.REAL)   // the next 30 s were Warming
        assertEquals(60.0, log.secondsAt(Level.COOL), 1e-9)
        assertEquals(30.0, log.secondsAt(Level.WARMING), 1e-9)
        assertEquals(1, log.changes().size)
        assertEquals(Level.WARMING, log.changes()[0].to)
        assertEquals(60.0, log.changes()[0].atSec, 1e-9)
        assertEquals(Level.WARMING, log.peakLevel)
        assertEquals(45, log.peakTss)
    }

    @Test fun aLongGapIsCountedAsNoReadingsNeverAsCoolTime() {
        val log = ShiftLog(maxGapSec = 120.0)
        log.add(0, Level.COOL, 10, Provenance.REAL)
        log.add(30 * 60 * sec, Level.COOL, 10, Provenance.REAL)  // phone was off for 30 minutes
        assertEquals(0.0, log.secondsAt(Level.COOL), 1e-9)
        assertEquals(1800.0, log.gapSec, 1e-9)
        assertTrue(log.levelsOnlySummary().contains("No readings for 30 min"))
    }

    @Test fun replayedOrSimulatedTimeNeverLooksReal() {
        val log = ShiftLog()
        log.add(0, Level.COOL, 10, Provenance.REAL)
        log.add(60 * sec, Level.COOL, 10, Provenance.REPLAY)
        log.add(120 * sec, Level.COOL, 10, Provenance.SIMULATED)
        assertFalse(log.allReal)
        assertEquals(0.5, log.shareFrom(Provenance.REAL), 1e-9)
        assertEquals(0.5, log.shareFrom(Provenance.REPLAY), 1e-9)
    }

    @Test fun theSupervisorSummaryHasLevelsOnly() {
        val log = ShiftLog(maxGapSec = 600.0)
        log.add(0, Level.STRAINED, 66, Provenance.REAL)
        log.add(300 * sec, Level.STRAINED, 70, Provenance.REAL)
        val s = log.levelsOnlySummary()
        assertTrue(s.contains("Strained: 5 min"))
        assertTrue(s.contains("Highest level: Strained"))
        assertFalse("no strain number leaves the phone", s.contains("70") || s.contains("66"))
        assertFalse(s.contains("bpm", ignoreCase = true))
        assertTrue(s.contains("not a medical device"))
    }

    @Test fun anEmptyLogSaysSo() {
        assertTrue(ShiftLog().isEmpty)
        assertEquals("No shift data yet.", ShiftLog().levelsOnlySummary())
    }
}
