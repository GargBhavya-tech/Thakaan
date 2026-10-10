package com.thakaan.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class ScgPipelineTest {
    /** A seated chest recording at 200 Hz: beat bursts on z, slow breathing sway on all axes, sensor noise, optional walking. */
    private fun recording(seconds: Double, hr: Double?, noise: Double = 0.01, walkAmp: Double = 0.0, seed: Long = 1): ScgRecording {
        val rnd = Random(seed)
        val fs = 200.0; val n = (seconds * fs).toInt()
        val beats = ArrayList<Double>()
        if (hr != null) { var t = 0.5; while (t < seconds) { beats += t; t += 60.0 / hr + rnd.nextGaussian() * 0.01 } }
        val t = DoubleArray(n) { it / fs }
        val z = DoubleArray(n) { i ->
            var v = 9.81 + 0.03 * sin(2 * PI * 0.25 * t[i]) + noise * rnd.nextGaussian() + walkAmp * sin(2 * PI * 2.0 * t[i])
            for (b in beats) { val k = t[i] - b; if (k > 0 && k < 0.2) v += 0.25 * exp(-k / 0.035) * sin(2 * PI * 16 * k) }
            v
        }
        val x = DoubleArray(n) { 0.02 * sin(2 * PI * 0.25 * t[it]) + noise * rnd.nextGaussian() + 0.3 * walkAmp * sin(2 * PI * 2.0 * t[it]) }
        val y = DoubleArray(n) { 0.5 + noise * rnd.nextGaussian() }
        val gyro = DoubleArray(n) { 0.02 + 0.01 * Math.abs(rnd.nextGaussian()) + 0.8 * walkAmp }
        return ScgRecording(t, x, y, z, gyro)
    }

    @Test fun aSeatedRecordingGivesTheHeartRateAndPicksTheChestAxis() {
        for (hr in listOf(58.0, 75.0, 95.0)) {
            val r = ScgPipeline.analyse(recording(45.0, hr))
            assertTrue("hr $hr: ${r.reason} still ${r.stillWindows}/${r.windows}", r.accepted)
            assertEquals(hr, r.classicalBpm!!, 4.0)
            assertEquals('z', r.axis)
        }
    }

    @Test fun walkingIsGatedOutNotMeasured() {
        val r = ScgPipeline.analyse(recording(45.0, 75.0, walkAmp = 2.0))
        assertFalse(r.accepted)
        assertNull(r.classicalBpm)
        assertTrue(r.reason!!, r.reason!!.contains("still"))
    }

    @Test fun noHeartbeatIsNeverReported() {
        var accepted = 0
        for (seed in 1L..10L) if (ScgPipeline.analyse(recording(45.0, null, noise = 0.03, seed = seed)).accepted) accepted++
        assertEquals(0, accepted)
    }

    @Test fun aShortRecordingIsRefused() {
        val r = ScgPipeline.analyse(recording(10.0, 75.0))
        assertFalse(r.accepted)
        assertTrue(r.reason!!.contains("short"))
    }

    @Test fun theDenoiserIsAppliedPerWindowAndReportedSeparately() {
        var calls = 0
        val r = ScgPipeline.analyse(recording(45.0, 75.0)) { w -> calls++; w }
        assertTrue(calls >= ScgPipeline.MIN_STILL_WINDOWS)
        assertNotNull(r.modelBpm)
        assertEquals(75.0, r.modelBpm!!, 4.0)
    }
}
