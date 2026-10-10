package com.thakaan.face

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

class FaceVitalsTest {
    /** Beats every [rrMs] ms (alternating +/- [swingMs]) drawn as smooth bumps, sampled at 30 Hz, wave only. */
    private fun wave(seconds: Double, rrMs: Double, swingMs: Double): Pair<DoubleArray, List<Double>> {
        val beats = ArrayList<Double>()
        var t = 0.4; var k = 0
        while (t < seconds) { beats += t; t += (rrMs + if (k++ % 2 == 0) swingMs else -swingMs) / 1000.0 }
        val x = DoubleArray((seconds * 30).toInt()) { i -> beats.sumOf { b -> exp(-0.5 * Math.pow((i / 30.0 - b) / 0.09, 2.0)) } }
        val m = x.average()
        return DoubleArray(x.size) { x[it] - m } to beats
    }

    @Test fun variabilityIsReadFromAlternatingIntervals() {
        // intervals alternate 900+40 / 900-40: successive differences are 80 ms, so RMSSD is 80
        val (w, _) = wave(70.0, 900.0, 40.0)
        val h = FaceVitals.hrv(FaceVitals.beatTimes(w, 30.0))
        assertNotNull(h)
        assertEquals(80.0, h!!.rmssdMs, 20.0)
        assertEquals(66.7, h.meanHrBpm, 2.0)
    }

    @Test fun aSteadyPulseHasLowVariability() {
        val (w, _) = wave(70.0, 800.0, 0.0)
        val h = FaceVitals.hrv(FaceVitals.beatTimes(w, 30.0))
        assertNotNull(h); assertTrue("rmssd ${h!!.rmssdMs}", h.rmssdMs < 25.0)
    }

    @Test fun tooFewBeatsGiveNoVariability() {
        val (w, _) = wave(12.0, 800.0, 0.0)
        assertNull(FaceVitals.hrv(FaceVitals.beatTimes(w, 30.0)))
    }

    @Test fun noiseGivesNoVariability() {
        val rnd = Random(4)
        assertNull(FaceVitals.hrv(FaceVitals.beatTimes(DoubleArray(2100) { rnd.nextGaussian() }, 30.0)))
    }

    private fun trace(seconds: Double, hr: Double, ampR: Double, ampG: Double, ampB: Double, seed: Long = 1): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val rnd = Random(seed); val n = (seconds * 30).toInt()
        fun ch(base: Double, amp: Double) = DoubleArray(n) { i -> base * (1 + amp * sin(2 * PI * hr / 60 * i / 30.0) + 0.0002 * rnd.nextGaussian()) }
        return Triple(ch(180.0, ampR), ch(120.0, ampG), ch(100.0, ampB))
    }

    @Test fun theRatioOfRatiosFollowsThePulseStrengthOfRedOverBlue() {
        val (r, g, b) = trace(40.0, 70.0, 0.002, 0.004, 0.004)
        val o = FaceVitals.oxygen(r, g, b, 30.0)!!
        assertEquals(0.5, o.ratio, 0.08)
        assertFalse(o.calibrated)
        assertTrue(o.estimatePct in 70.0..100.0)
    }

    @Test fun aCalibrationOffsetShiftsTheEstimateAndIsMarked() {
        val (r, g, b) = trace(40.0, 70.0, 0.002, 0.004, 0.004)
        val plain = FaceVitals.oxygen(r, g, b, 30.0)!!
        val cal = FaceVitals.oxygen(r, g, b, 30.0, calibrationOffsetPct = -3.0)!!
        assertTrue(cal.calibrated)
        assertEquals(plain.estimatePct - 3.0, cal.estimatePct, 1e-9)
    }

    @Test fun noPulseMeansNoOxygenNumber() {
        // the oxygen ratio is only computed once a pulse has been confirmed, so a face with no pulse shows nothing
        val (r, g, b) = trace(40.0, 70.0, 0.0, 0.0, 0.0)
        val frames = List(r.size) { FaceFrame(1_000_000_000L + (it * 1e9 / 30).toLong(), r[it], g[it], b[it], true, 0.0) }
        val s = FaceVitals.snapshot(frames, emptyList(), FatigueCalibration(), null, null)
        assertFalse(s.pulse!!.accepted); assertNull(s.oxygen); assertNull(s.hrv)
    }

    private fun faceFrames(seconds: Double, hr: Double): List<FaceFrame> {
        val (r, g, b) = trace(seconds, hr, 0.0015, 0.004, 0.003)
        return List(r.size) { FaceFrame(1_000_000_000L + (it * 1e9 / 30).toLong(), r[it], g[it], b[it], true, 0.0) }
    }

    @Test fun aFullSnapshotShowsPulseOxygenAndWaitsForVariability() {
        val s = FaceVitals.snapshot(faceFrames(35.0, 72.0), emptyList(), FatigueCalibration(), null, null)
        assertTrue("pulse ${s.pulse?.reasons}", s.pulse!!.accepted)
        assertEquals(72.0, s.pulse!!.hrBpm!!, 3.0)
        assertNotNull(s.oxygen)
        assertNull(s.hrv)
        assertTrue(s.hrvWaitSec in 20..30)
        assertEquals(100, s.facePercent)
    }

    @Test fun withoutAFaceThereAreNoVitalsAndNothingIsClaimed() {
        val none = List(300) { FaceFrame(1_000_000_000L + (it * 1e9 / 30).toLong(), 0.0, 0.0, 0.0, false, 0.0) }
        val s = FaceVitals.snapshot(none, emptyList(), FatigueCalibration(), null, null)
        assertFalse(s.pulse!!.accepted); assertNull(s.oxygen); assertNull(s.hrv); assertEquals(0, s.facePercent)
    }
}
