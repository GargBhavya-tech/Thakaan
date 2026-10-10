package com.thakaan.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * What a real chest recording looked like (2026-10-10): the phone is lifted onto the chest in the first seconds, which tilts it
 * and shakes it, and the 30 s posture lockout then drops every window where the phone is already still. The pulse is in those
 * windows. The rule stays (it has not been checked against a reference pulse); the result must say what was under it.
 */
class ScgLockoutTest {
    /** 45 s at 200 Hz: still on a table-like chest except for a lift at [liftAtSec] that rotates gravity by [tiltDeg]. */
    private fun recording(hr: Double?, liftAtSec: Double?, tiltDeg: Double = 20.0, seed: Long = 5): ScgRecording {
        val rnd = Random(seed); val fs = 200.0; val n = (45 * fs).toInt()
        val beats = ArrayList<Double>()
        if (hr != null) { var t = 0.5; while (t < 45) { beats += t; t += 60.0 / hr + rnd.nextGaussian() * 0.01 } }
        val t = DoubleArray(n) { it / fs }
        val x = DoubleArray(n); val y = DoubleArray(n); val z = DoubleArray(n); val g = DoubleArray(n)
        for (i in 0 until n) {
            val after = liftAtSec != null && t[i] > liftAtSec
            val ang = if (after) Math.toRadians(tiltDeg) else 0.0
            var burst = 0.0
            for (b in beats) { val k = t[i] - b; if (k > 0 && k < 0.2) burst += 0.25 * exp(-k / 0.035) * sin(2 * PI * 16 * k) }
            val lift = if (liftAtSec != null && t[i] > liftAtSec - 1.0 && t[i] < liftAtSec + 1.0) 1.5 * sin(2 * PI * 1.5 * (t[i] - liftAtSec)) else 0.0
            val sway = 0.03 * sin(2 * PI * 0.25 * t[i])
            val vertical = 9.81 + sway + burst + lift + 0.01 * rnd.nextGaussian()
            z[i] = vertical * cos(ang); y[i] = 0.5 + vertical * sin(ang) + 0.01 * rnd.nextGaussian(); x[i] = 0.01 * rnd.nextGaussian()
            g[i] = 0.02 + 0.01 * Math.abs(rnd.nextGaussian()) + (if (lift != 0.0) 0.9 else 0.0)
        }
        return ScgRecording(t, x, y, z, g)
    }

    @Test fun aPhoneLiftedOntoTheChestIsNotAcceptedButTheLabSaysWhatWasUnderTheLockout() {
        val r = ScgPipeline.analyse(recording(78.0, liftAtSec = 4.0))
        assertFalse("the lockout still drops the recording", r.accepted)
        assertNull(r.classicalBpm)
        assertTrue("the lockout cost windows: ${r.lostToLockout}", r.lostToLockout >= 8)
        assertNotNull("a steady pulse under the lockout is reported as unconfirmed", r.unconfirmedBpm)
        assertEquals(78.0, r.unconfirmedBpm!!, 4.0)
    }

    @Test fun aRecordingWithoutALiftIsAcceptedAsBeforeAndHasNoUnconfirmedRate() {
        val r = ScgPipeline.analyse(recording(78.0, liftAtSec = null))
        assertTrue(r.reason ?: "", r.accepted)
        assertEquals(78.0, r.classicalBpm!!, 4.0)
        assertNull(r.unconfirmedBpm)
        assertEquals(0, r.lostToLockout)
    }

    @Test fun noHeartbeatUnderTheLockoutGivesNoUnconfirmedRateEither() {
        var shown = 0
        for (seed in 1L..8L) if (ScgPipeline.analyse(recording(null, liftAtSec = 4.0, seed = seed)).unconfirmedBpm != null) shown++
        assertEquals(0, shown)
    }

    @Test fun movementIsCountedSoARejectedRecordingSaysWhy() {
        val r = ScgPipeline.analyse(recording(78.0, liftAtSec = 4.0))
        assertTrue("lift motion and rotation are counted: ${r.lostToMotion} / ${r.lostToRotation}", r.lostToMotion >= 1 && r.lostToRotation >= 1)
    }

    @Test fun aDriftingButRealPulseIsShownAsUnconfirmedWithItsSpreadAndNeverAccepted() {
        // 45 s of a pulse that drifts from 58 to 86 over the recording: real, but far from within 4 beats of one middle value
        val rnd = java.util.Random(9); val fs = 200.0; val n = (45 * fs).toInt()
        val beats = ArrayList<Double>(); var tb = 0.5
        while (tb < 45) { val hr = 58 + 28 * (tb / 45); beats += tb; tb += 60.0 / hr }
        val t = DoubleArray(n) { it / fs }
        val z = DoubleArray(n) { i -> var v = 9.81 + 0.02 * kotlin.math.sin(2 * PI * 0.25 * t[i]) + 0.01 * rnd.nextGaussian(); for (b in beats) { val k = t[i] - b; if (k > 0 && k < 0.2) v += 0.25 * exp(-k / 0.035) * sin(2 * PI * 16 * k) }; v }
        val r = ScgPipeline.analyse(ScgRecording(t, DoubleArray(n) { 0.01 * rnd.nextGaussian() }, DoubleArray(n) { 0.5 + 0.01 * rnd.nextGaussian() }, z, DoubleArray(n) { 0.02 }))
        assertFalse("drift beyond the tolerance is not accepted", r.accepted)
        assertNotNull("but the estimate is shown with its spread", r.unconfirmedBpm)
        assertTrue(r.unconfirmedWhy!!.contains("ranged from"))
        assertEquals(72.0, r.unconfirmedBpm!!, 10.0)
    }
}
