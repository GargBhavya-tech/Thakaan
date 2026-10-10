package com.thakaan.shift

import com.thakaan.risk.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckScheduleTest {
    @Test fun theHotterTheLevelTheSoonerTheNextCheck() {
        val m = Level.entries.map { CheckSchedule.intervalMin(it) }
        assertEquals(m.sortedDescending(), m)
        assertTrue(m.first() > m.last())
    }

    @Test fun neverCheckedMeansDueNow() {
        assertTrue(CheckSchedule.dueInSec(null, 1_000L, Level.COOL) <= 0)
        assertEquals("Check due now", CheckSchedule.dueText(0))
    }

    @Test fun aFreshCheckIsDueAfterTheInterval() {
        assertEquals(45 * 60L, CheckSchedule.dueInSec(1_000L, 1_000L, Level.COOL))
        assertEquals(0L, CheckSchedule.dueInSec(1_000L, 1_000L + 45 * 60, Level.COOL))
    }

    @Test fun aRisingLevelBringsTheNextCheckForward() {
        val cool = CheckSchedule.dueInSec(0L, 600L, Level.COOL)
        val strained = CheckSchedule.dueInSec(0L, 600L, Level.STRAINED)
        assertTrue(strained < cool)
        assertTrue("an old check at Strained is overdue", CheckSchedule.dueInSec(0L, 20 * 60L, Level.STRAINED) < 0)
    }

    @Test fun theTextIsPlain() {
        assertEquals("Next check in 24 min", CheckSchedule.dueText(24 * 60L))
        assertEquals("Next check in under 2 min", CheckSchedule.dueText(60L))
        assertEquals("Check due now", CheckSchedule.dueText(-30))
    }
}
