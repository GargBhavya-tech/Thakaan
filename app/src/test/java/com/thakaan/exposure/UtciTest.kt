package com.thakaan.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UtciTest {
    @Test fun matchesPythermalcomfortOnTheReferenceCases() {
        for (c in Utci.REFERENCE) {
            val got = Utci.compute(c[0], c[1], c[2], c[3])!!
            assertEquals("tdb=${c[0]} tr=${c[1]} v=${c[2]} rh=${c[3]}", c[4], got, 1e-6)
        }
    }

    @Test fun hotterRadiantLoadGivesHigherIndex() {
        val shade = Utci.compute(35.0, 35.0, 1.5, 40.0)!!
        val sun = Utci.compute(35.0, 60.0, 1.5, 40.0)!!
        assertTrue(sun > shade + 5)
    }

    @Test fun outOfEnvelopeInputsGiveNull() {
        assertNull(Utci.compute(60.0, 60.0, 1.0, 50.0))
        assertNull(Utci.compute(30.0, 30.0, 20.0, 50.0))
        assertNull(Utci.compute(30.0, 30.0, 1.0, 120.0))
        assertNull(Utci.compute(30.0, 130.0, 1.0, 50.0))
    }

    @Test fun calmAirIsClampedToTheModelFloor() {
        assertEquals(Utci.compute(30.0, 30.0, 0.5, 50.0)!!, Utci.compute(30.0, 30.0, 0.1, 50.0)!!, 1e-12)
    }

    @Test fun categories() {
        assertEquals("no heat stress", Utci.category(20.0))
        assertEquals("strong heat stress", Utci.category(35.0))
        assertEquals("extreme heat stress", Utci.category(50.0))
    }
}
