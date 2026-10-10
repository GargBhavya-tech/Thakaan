package com.thakaan.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckInTest {
    private class Mem(var c: DailyCheckIn? = null) : CheckInStore {
        override fun load() = c
        override fun save(c: DailyCheckIn) { this.c = c }
    }

    @Test fun roundTripKeepsUnknownAsUnknown() {
        val c = DailyCheckIn("2026-10-10", sleptWell = true, ate = null, unwell = false)
        val back = DailyCheckIn.fromJson(c.toJson())!!
        assertEquals("2026-10-10", back.date)
        assertEquals(true, back.sleptWell); assertNull(back.ate); assertEquals(false, back.unwell)
    }

    @Test fun unknownSleepIsNotABenignCause() {
        assertNull(DailyCheckIn("2026-10-10", null, null, null).sleptPoorly)
        assertEquals(true, DailyCheckIn("2026-10-10", false, null, null).sleptPoorly)
        assertEquals(false, DailyCheckIn("2026-10-10", true, null, null).sleptPoorly)
    }

    @Test fun dueOncePerDay() {
        val s = Mem()
        assertTrue(checkInDue(s, "2026-10-10"))
        s.save(DailyCheckIn("2026-10-10", null, null, null)) // a skip stores an all-unknown entry
        assertFalse(checkInDue(s, "2026-10-10"))
        assertTrue(checkInDue(s, "2026-10-11"))
    }

    @Test fun garbageIsRejected() {
        assertNull(DailyCheckIn.fromJson("not json"))
        assertNull(DailyCheckIn.fromJson("{\"date\":\"yesterday\"}"))
        assertNull(DailyCheckIn.fromJson("{\"slept\":true}"))
    }
}
