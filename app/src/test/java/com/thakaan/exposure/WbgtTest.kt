package com.thakaan.exposure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WbgtTest {
    private fun input(t: Double = 34.0, rh: Double = 45.0, solar: Double = 900.0, wind: Double = 1.5, p: Double = 910.0) =
        WbgtInput(1_760_000_000_000L, 12.97, 77.59, solar, p, t, rh, wind)

    private val ok = { _: WbgtInput -> doubleArrayOf(0.0, 32.15, 51.65, 26.32, 1.5) }

    @Test fun goodInputsPassThroughTheBackend() {
        val o = Wbgt(ok).compute(input())
        assertNotNull(o); assertEquals(32.15, o!!.wbgtC, 1e-9); assertEquals(51.65, o.globeC, 1e-9)
    }

    @Test fun nativeLibraryMissingGivesNullNotAGuess() {
        // on the host JVM the .so cannot load; the production backend must answer null
        assertNull(Wbgt().compute(input()))
    }

    @Test fun backendFailureStatusIsNull() {
        assertNull(Wbgt { doubleArrayOf(-1.0, 30.0, 40.0, 25.0, 1.0) }.compute(input()))
        assertNull(Wbgt { null }.compute(input()))
        assertNull(Wbgt { throw UnsatisfiedLinkError("x") }.compute(input()))
        assertNull(Wbgt { doubleArrayOf(0.0, Double.NaN, 40.0, 25.0, 1.0) }.compute(input()))
        assertNull(Wbgt { doubleArrayOf(0.0) }.compute(input()))
    }

    @Test fun outOfRangeInputsAreRejectedBecauseTheCLibraryAcceptsThem() {
        // measured on the host: RH 150 % still returned WBGT 43.3 from the C code
        for (bad in listOf(input(rh = 150.0), input(rh = -1.0), input(t = 90.0), input(solar = -5.0), input(wind = -1.0), input(p = 100.0), input(t = Double.NaN))) {
            assertNull(Wbgt(ok).compute(bad))
        }
        assertTrue(Wbgt.validInputs(input()))
    }
}
