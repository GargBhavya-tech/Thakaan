package com.thakaan.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class FaceRppgRegionsTest {
    /** Three skin regions with the same weak pulse, their own camera noise, and a slow lighting drift common to all. */
    private fun frames(seconds: Double, hr: Double?, noise: Double = 0.0015, drift: Double = 0.02, seed: Long = 1, hrAfter: Double? = null): List<FaceFrame> {
        val rnd = Random(seed)
        val n = (seconds * 30).toInt()
        var phase = 0.0
        return List(n) { i ->
            val t = i / 30.0
            val rate = if (hr == null) 0.0 else if (hrAfter != null && t > seconds / 2) hrAfter else hr
            phase += 2 * PI * rate / 60.0 / 30.0
            val pulse = if (hr == null) 0.0 else sin(phase)
            val common = drift * sin(2 * PI * 0.15 * t) + 0.01 * sin(2 * PI * 0.35 * t)
            fun region(base: DoubleArray) = DoubleArray(3) { c ->
                val w = doubleArrayOf(0.25, 1.0, 0.6)[c]
                base[c] * (1 + w * 0.0025 * pulse + (1.0 + 0.1 * c) * common + noise * rnd.nextGaussian())
            }
            val regs = listOf(region(doubleArrayOf(143.0, 105.0, 91.0)), region(doubleArrayOf(146.0, 108.0, 90.0)), region(doubleArrayOf(157.0, 120.0, 102.0)))
            FaceFrame(1_000_000_000L + (t * 1e9).toLong(), regs.sumOf { it[0] } / 3, regs.sumOf { it[1] } / 3, regs.sumOf { it[2] } / 3, true, 0.001, regs)
        }
    }

    @Test fun aSteadyWeakPulseOnThreeRegionsIsAccepted() {
        val r = FaceRppgRegions.analyse(frames(40.0, 84.0))!!
        assertTrue("reasons ${r.reasons} estimate ${r.estimateBpm}", r.accepted)
        assertEquals(84.0, r.hrBpm!!, 3.0)
    }

    @Test fun severalRatesAreRecovered() {
        for (hr in listOf(55.0, 70.0, 100.0, 130.0)) {
            val r = FaceRppgRegions.analyse(frames(40.0, hr))!!
            assertTrue("hr $hr: ${r.reasons} est ${r.estimateBpm}", r.accepted)
            assertEquals(hr, r.hrBpm!!, 3.0)
        }
    }

    @Test fun noPulseIsNeverAccepted() {
        var accepted = 0
        for (seed in 1L..20L) if (FaceRppgRegions.analyse(frames(40.0, null, noise = 0.003, seed = seed))!!.accepted) accepted++
        assertEquals(0, accepted)
    }

    @Test fun aRateThatChangesPartwayIsRejectedNotAveraged() {
        val r = FaceRppgRegions.analyse(frames(40.0, 60.0, hrAfter = 100.0))!!
        assertFalse("estimate ${r.estimateBpm}", r.accepted)
        assertNull(r.hrBpm)
    }

    @Test fun aShortCaptureIsTooShort() {
        val r = FaceRppgRegions.analyse(frames(15.0, 84.0))!!
        assertFalse(r.accepted)
        assertTrue(r.reasons.contains(FaceReason.TOO_SHORT))
    }

    /** Like [frames] but with a breathing wobble in the skin (not the background) and lighting drift in both. */
    private fun breathingFrames(seconds: Double, breathPerMin: Double?, seed: Long = 1): List<FaceFrame> {
        val rnd = Random(seed)
        val n = (seconds * 30).toInt()
        return List(n) { i ->
            val t = i / 30.0
            val drift = 0.02 * sin(2 * PI * 0.07 * t) + 0.01 * sin(2 * PI * 0.19 * t + 1.0)
            val breath = if (breathPerMin == null) 0.0 else 0.004 * sin(2 * PI * breathPerMin / 60.0 * t)
            fun region(base: DoubleArray) = DoubleArray(3) { c -> base[c] * (1 + drift + breath * (1 + 0.2 * c) + 0.0015 * rnd.nextGaussian()) }
            val regs = listOf(region(doubleArrayOf(143.0, 105.0, 91.0)), region(doubleArrayOf(146.0, 108.0, 90.0)), region(doubleArrayOf(157.0, 120.0, 102.0)))
            val bg = DoubleArray(3) { c -> doubleArrayOf(208.0, 201.0, 187.0)[c] * (1 + drift + 0.0008 * rnd.nextGaussian()) }
            FaceFrame(1_000_000_000L + (t * 1e9).toLong(), regs.sumOf { it[0] } / 3, regs.sumOf { it[1] } / 3, regs.sumOf { it[2] } / 3, true, 0.001, regs, bg)
        }
    }

    @Test fun aBreathingRateIsFoundAmongLightingDrift() {
        for (rate in listOf(10.0, 14.0, 20.0)) {
            val b = FaceRppgRegions.breathing(breathingFrames(60.0, rate))
            assertNotNull("rate $rate: agreeing ${b.agreeing} of ${b.traces}", b.perMin)
            assertEquals(rate, b.perMin!!, 1.5)
        }
    }

    @Test fun noBreathingIsNotReportedFromDriftAndNoise() {
        var reported = 0
        for (seed in 1L..20L) if (FaceRppgRegions.breathing(breathingFrames(60.0, null, seed)).perMin != null) reported++
        assertTrue("false breathing reports: $reported of 20", reported <= 1)
    }

    @Test fun breathingNeedsThirtySeconds() {
        assertNull(FaceRppgRegions.breathing(breathingFrames(20.0, 14.0)).perMin)
    }

    private fun load(name: String): List<FaceFrame>? {
        val dir = System.getenv("FACE_FIXTURES") ?: return null
        val f = File(dir, name); if (!f.exists()) return null
        return f.readLines().drop(1).filter { it.isNotBlank() }.map { l ->
            val c = l.split(',').map { it.toDouble() }
            FaceFrame(c[0].toLong() * 1_000_000L, c[1], c[2], c[3], c[4] > 0.5, c[5],
                listOf(doubleArrayOf(c[9], c[10], c[11]), doubleArrayOf(c[12], c[13], c[14]), doubleArrayOf(c[15], c[16], c[17])), doubleArrayOf(c[18], c[19], c[20]))
        }
    }

    @Test fun theThirdRealRecordingShowsPulseBreathingAndVariability() {
        val fr = load("face_live_1791617309370.csv"); assumeTrue("no fixture", fr != null)
        var dbg = ""
        FaceRppgRegions.analyse(fr!!.filter { (fr.last().tNanos - it.tNanos) / 1e9 <= FaceVitals.PULSE_WINDOW_SEC }, debug = { a, b -> dbg = "pos " + a.map { it.toInt() } + " chrom " + b.map { it.toInt() } })
        val s = FaceVitals.snapshot(fr, emptyList(), FatigueCalibration(), null, null)
        assertTrue("pulse ${s.pulse?.reasons} est ${s.pulse?.estimateBpm} $dbg", s.pulse!!.accepted)
        assertEquals(83.5, s.pulse!!.hrBpm!!, 2.5)
        val bb = FaceRppgRegions.breathing(fr)
        assertNotNull("breathing: agreeing ${bb.agreeing} of ${bb.traces}", s.pulse!!.respPerMin)
        assertTrue("breathing ${s.pulse!!.respPerMin}", s.pulse!!.respPerMin!! in 11.0..16.0) // no reference count yet: a plausible resting range
        val w = FaceRppgRegions.pulseWave(fr.filter { (fr.last().tNanos - it.tNanos) / 1e9 <= 72 }, 83.5 / 60.0)
        val bt = if (w == null) emptyList() else FaceVitals.beatTimes(w, 30.0, 0.6 * 60 / 83.5)
        val rr = bt.zipWithNext { a, b2 -> ((b2 - a) * 1000).toInt() }
        assertNotNull("variability: wave ${w?.size} beats ${bt.size} rr $rr hrvwait ${s.hrvWaitSec} secs ${s.seconds}", s.hrv)
        assertTrue("rmssd ${s.hrv!!.rmssdMs}", s.hrv!!.rmssdMs in 15.0..120.0)
    }

    @Test fun framesWithoutRegionsFallBackToThePooledMethod() {
        val pooled = frames(40.0, 84.0).map { FaceFrame(it.tNanos, it.r, it.g, it.b, it.faceFound, it.motion) }
        assertNull(FaceRppgRegions.analyse(pooled))
    }

    /** Real recording from the iQOO 15 (face_live_*.csv with region columns), only run when FACE_FIXTURES points at the folder. */
    @Test fun theRealRecordingsBestEstimateIsNearEightyFour() {
        val dir = System.getenv("FACE_FIXTURES"); assumeTrue("no fixtures", dir != null)
        val f = File(dir, "face_live_1791616760759.csv"); assumeTrue("no file", f.exists())
        val fr = f.readLines().drop(1).filter { it.isNotBlank() }.map { l ->
            val c = l.split(',').map { it.toDouble() }
            FaceFrame(c[0].toLong() * 1_000_000L, c[1], c[2], c[3], c[4] > 0.5, c[5],
                listOf(doubleArrayOf(c[9], c[10], c[11]), doubleArrayOf(c[12], c[13], c[14]), doubleArrayOf(c[15], c[16], c[17])))
        }
        var dbg = ""
        val r = FaceRppgRegions.analyse(fr, debug = { a, b -> dbg = "pos " + a.map { it.toInt() } + " chrom " + b.map { it.toInt() } })
        assertNotNull(r)
        // acceptance is deliberately not asserted: the gate sits just above this recording (73% of cross-check windows agree, 75% needed)
        // and is not loosened without a reference heart rate. The best estimate is what a person sees as "about N (not confirmed)".
        assertNotNull("no estimate $dbg", r!!.estimateBpm)
        assertEquals(83.5, r.estimateBpm!!, 2.0)
    }
}
