package com.thakaan.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class FingertipPosTest {
    /**
     * A synthetic fingertip: base colour like a real capture (red 142, green 88, blue 65), a pulse that is strongest in green
     * and weakest in red, a slow pressure drift that moves the colours by slightly different amounts, optional periodic
     * hand tremor, and optional change of heart rate part-way through.
     */
    private fun gen(
        hr: Double?, seconds: Double = 35.0, seed: Long = 1, amp: Double = 0.0004, noise: Double = 0.0003,
        drift: Double = 0.02, tremorHz: Double? = null, tremorAmp: Double = 0.003, hrAfter: Double? = null, hrChangeAt: Double = 17.0,
        blue: Boolean = true,
    ): List<PpgFrame> {
        val rnd = Random(seed)
        val out = ArrayList<PpgFrame>()
        val n = (seconds * 30).toInt()
        var walk = 0.0
        var phase = 0.0
        for (k in 0 until n) {
            val t = k / 30.0 + rnd.nextGaussian() * 0.002
            val rate = if (hr == null) 0.0 else if (hrAfter != null && t >= hrChangeAt) hrAfter else hr
            phase += 2 * PI * rate / 60.0 / 30.0
            val p = if (hr == null) 0.0 else sin(phase)
            walk += 0.0015 * rnd.nextGaussian()
            val common = drift * sin(2 * PI * 0.17 * t) + walk.coerceIn(-0.04, 0.04) +
                (if (tremorHz != null) tremorAmp * sin(2 * PI * tremorHz * t) else 0.0)
            fun ch(base: Double, pulseW: Double, commonGain: Double) =
                base * (1 - (pulseW * amp * p) + commonGain * common + noise * rnd.nextGaussian())
            out += PpgFrame(
                1_000_000_000L + (t * 1e9).toLong(),
                ch(142.0, 0.25, 1.0), ch(88.0, 1.0, 1.15), if (blue) ch(65.0, 0.6, 1.3) else 0.0,
            )
        }
        return out
    }

    @Test fun aVeryWeakPulseWithPressureDriftIsAccepted() {
        val r = FingertipPos.analyse(gen(62.0))!!
        assertTrue("reason ${r.reason} frac ${r.consistentFraction} windows ${r.windowHrs.map { it.toInt() }}", r.accepted)
        assertEquals(62.0, r.hrBpm!!, 3.0)
    }

    @Test fun severalRatesAreRecovered() {
        for (hr in listOf(50.0, 75.0, 110.0)) {
            val r = FingertipPos.analyse(gen(hr, amp = 0.002))!!
            assertTrue("hr $hr: ${r.reason} ${r.windowHrs.map { it.toInt() }}", r.accepted)
            assertEquals("hr $hr", hr, r.hrBpm!!, 3.0)
        }
    }

    @Test fun noPulseIsNeverAccepted() {
        var accepted = 0
        for (seed in 1L..30L) if (FingertipPos.analyse(gen(null, seed = seed, noise = 0.0005))!!.accepted) accepted++
        assertEquals(0, accepted)
    }

    @Test fun aSteadyHandTremorAloneIsNeverAPulse() {
        var accepted = 0
        for (seed in 1L..15L) if (FingertipPos.analyse(gen(null, seed = seed, tremorHz = 1.0, tremorAmp = 0.004))!!.accepted) accepted++
        assertEquals("a periodic movement common to all colours is not a pulse", 0, accepted)
    }

    @Test fun aRateThatWandersIsRejectedNotAveraged() {
        val r = FingertipPos.analyse(gen(60.0, amp = 0.002, hrAfter = 100.0, hrChangeAt = 17.0))!!
        assertFalse("windows ${r.windowHrs.map { it.toInt() }}", r.accepted)
        assertNull(r.hrBpm)
    }

    @Test fun capturesWithoutABlueChannelFallBackToTheOldMethod() {
        assertNull(FingertipPos.analyse(gen(62.0, blue = false)))
    }

    @Test fun aShortCaptureHasTooFewWindows() {
        val r = FingertipPos.analyse(gen(62.0, seconds = 14.0))
        assertNotNull(r)
        assertFalse(r!!.accepted)
    }
}
