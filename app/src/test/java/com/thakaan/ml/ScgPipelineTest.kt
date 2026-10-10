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

    /** Same seated recording but the phone sits tilted in the pocket: the beat leans between y and z, and gravity can move at [shiftAt]. */
    private fun tilted(seconds: Double, hr: Double, tiltDeg: Double, shiftAt: Double? = null, gapFrom: Double? = null, seed: Long = 3): ScgRecording {
        val base = recording(seconds, hr, seed = seed)
        val c = Math.cos(Math.toRadians(tiltDeg)); val s = Math.sin(Math.toRadians(tiltDeg))
        val beat = DoubleArray(base.t.size) { base.az[it] - 9.81 }
        var y = DoubleArray(base.t.size) { base.ay[it] + beat[it] * s + 9.81 * s }
        var z = DoubleArray(base.t.size) { 9.81 * c + beat[it] * c }
        if (shiftAt != null) for (i in base.t.indices) if (base.t[i] >= shiftAt) { val yy = y[i]; y[i] = z[i]; z[i] = yy }
        val keep = base.t.indices.filter { gapFrom == null || base.t[it] < gapFrom || base.t[it] > gapFrom + 0.5 }
        fun pick(v: DoubleArray) = DoubleArray(keep.size) { v[keep[it]] }
        return ScgRecording(pick(base.t), pick(base.ax), pick(y), pick(z), pick(base.gyroMag!!))
    }

    @Test fun aTiltedPhoneStillGivesTheHeartRate() {
        for (tilt in listOf(25.0, 60.0, 85.0)) {
            val r = ScgPipeline.analyse(tilted(45.0, 72.0, tilt))
            assertTrue("tilt $tilt: ${r.reason} still ${r.stillWindows}/${r.windows}", r.accepted)
            assertEquals(72.0, r.classicalBpm!!, 4.0)
        }
    }

    @Test fun aPostureChangeSilencesTheNextThirtySeconds() {
        val r = ScgPipeline.analyse(tilted(45.0, 72.0, 0.0, shiftAt = 10.0))
        assertFalse("posture change at 10 s leaves too little settled data: ${r.reason}", r.accepted)
        assertTrue(r.reason!!, r.reason!!.contains("still"))
    }

    @Test fun aHoleInTheSensorStreamDropsOnlyTheWindowsItTouches() {
        val r = ScgPipeline.analyse(tilted(45.0, 72.0, 0.0, gapFrom = 20.0))
        assertTrue(r.reason ?: "", r.accepted)
        assertTrue("windows touching the gap are dropped (${r.stillWindows}/${r.windows})", r.stillWindows < r.windows)
        assertEquals(72.0, r.classicalBpm!!, 4.0)
    }

    @Test fun breathingComesOutAsAByProduct() {
        val r = ScgPipeline.analyse(recording(45.0, 75.0))
        assertNotNull(r.breathsPerMin)
        assertEquals(15.0, r.breathsPerMin!!, 3.0) // the generator sways at 0.25 Hz
        assertTrue(r.motionQuality > 0.5)
    }

    @Test fun anAdaptiveCancellerRemovesGyroCorrelatedShaking() {
        val n = 4000
        val rnd = Random(5)
        val ref = DoubleArray(n) { rnd.nextGaussian() }
        val truth = DoubleArray(n) { 0.3 * sin(2 * PI * 1.3 * it / 100.0) }
        val noisy = DoubleArray(n) { truth[it] + 1.5 * ref[it] }
        val out = Nlms.cancel(noisy, listOf(ref))
        val tail = 1000 until n
        val before = tail.sumOf { (noisy[it] - truth[it]) * (noisy[it] - truth[it]) }
        val after = tail.sumOf { (out[it] - truth[it]) * (out[it] - truth[it]) }
        assertTrue("error $before -> $after", after < 0.2 * before)
    }

    @Test fun aBeatPairPatternIsNeverReadAsDoubleTheRate() {
        // two bursts 0.25 s apart every 0.8 s (75 bpm); reading it as 150 would be the classic harmonic error
        val rec = recording(45.0, null)
        val n = rec.t.size
        val z = rec.az.copyOf()
        var b = 0.5
        while (b < 45.0) {
            for (off in listOf(0.0, 0.25)) for (i in 0 until n) { val k = rec.t[i] - (b + off); if (k > 0 && k < 0.2) z[i] += 0.25 * exp(-k / 0.035) * sin(2 * PI * 16 * k) }
            b += 0.8
        }
        val r = ScgPipeline.analyse(ScgRecording(rec.t, rec.ax, rec.ay, z, rec.gyroMag))
        if (r.accepted) assertEquals(75.0, r.classicalBpm!!, 4.0)
    }

    @Test fun theStreamGivesTheSameRateAndKeepsTheWorstProvenance() {
        val rec = recording(60.0, 75.0)
        fun feed(prov: (Int) -> com.thakaan.core.Provenance): List<ScgReading> {
            val s = ScgStream(); val out = ArrayList<ScgReading>()
            for (i in rec.t.indices) {
                val tn = (rec.t[i] * 1e9).toLong() + 1_000_000_000L
                s.onReading(com.thakaan.sensorhub.Reading(com.thakaan.sensorhub.Stream.GYRO, tn, floatArrayOf(0.02f, 0f, 0f), prov(i)))
                s.onReading(com.thakaan.sensorhub.Reading(com.thakaan.sensorhub.Stream.ACCEL, tn, floatArrayOf(rec.ax[i].toFloat(), rec.ay[i].toFloat(), rec.az[i].toFloat()), prov(i)))?.let { out += it }
            }
            return out
        }
        val real = feed { com.thakaan.core.Provenance.REAL }
        assertTrue(real.isNotEmpty())
        assertEquals(75.0, real.last().bpm!!, 4.0)
        assertTrue(real.all { it.provenance == com.thakaan.core.Provenance.REAL })
        val mixed = feed { if (it == 3000) com.thakaan.core.Provenance.REPLAY else com.thakaan.core.Provenance.REAL }
        assertTrue("one replay sample inside the window must show", mixed.last().provenance == com.thakaan.core.Provenance.REPLAY)
    }

    @Test fun theStreamStaysSilentWhileWalking() {
        val rec = recording(60.0, 75.0, walkAmp = 2.0)
        val s = ScgStream(); var anyBpm = false
        for (i in rec.t.indices) {
            val tn = (rec.t[i] * 1e9).toLong() + 1_000_000_000L
            s.onReading(com.thakaan.sensorhub.Reading(com.thakaan.sensorhub.Stream.GYRO, tn, floatArrayOf(rec.gyroMag!![i].toFloat(), 0f, 0f), com.thakaan.core.Provenance.REAL))
            s.onReading(com.thakaan.sensorhub.Reading(com.thakaan.sensorhub.Stream.ACCEL, tn, floatArrayOf(rec.ax[i].toFloat(), rec.ay[i].toFloat(), rec.az[i].toFloat()), com.thakaan.core.Provenance.REAL))?.let { if (it.bpm != null) anyBpm = true }
        }
        assertFalse(anyBpm)
    }

    @Test fun theDenoiserIsAppliedPerWindowAndReportedSeparately() {
        var calls = 0
        val r = ScgPipeline.analyse(recording(45.0, 75.0)) { w -> calls++; w }
        assertTrue(calls >= ScgPipeline.MIN_STILL_WINDOWS)
        assertNotNull(r.modelBpm)
        assertEquals(75.0, r.modelBpm!!, 4.0)
    }
}
