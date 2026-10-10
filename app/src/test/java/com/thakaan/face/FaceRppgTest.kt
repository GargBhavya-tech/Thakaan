package com.thakaan.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class FaceRppgTest {
    /** Synthetic face: skin colour with a pulse (green strongest, red weakest), noise, brightness wobble and optional breathing. */
    private fun gen(
        hr: Double?, seconds: Double, seed: Long = 1, fs: Double = 30.0,
        pulseAmp: Double = 0.003, noise: Double = 0.0006, wobbleAmp: Double = 0.0, wobbleHz: Double = 1.7,
        respHz: Double? = null, respAmp: Double = 0.008, base: Triple<Double, Double, Double> = Triple(180.0, 120.0, 100.0),
        faceEvery: Int = 1, motion: Double = 0.0, dropEvery: Int = 0,
    ): List<FaceFrame> {
        val rnd = Random(seed)
        val out = ArrayList<FaceFrame>()
        val n = (seconds * fs).toInt()
        for (k in 0 until n) {
            if (dropEvery > 0 && k % (dropEvery * 2) == 0) continue
            val t = k / fs + rnd.nextGaussian() * 0.002
            val p = if (hr != null) sin(2 * PI * hr / 60.0 * t) else 0.0
            val common = wobbleAmp * sin(2 * PI * wobbleHz * t)
            val resp = if (respHz != null) respAmp * sin(2 * PI * respHz * t) else 0.0
            fun c(b: Double, w: Double, respW: Double) = b * (1 + w * pulseAmp * p + common + respW * resp + noise * rnd.nextGaussian())
            out += FaceFrame(
                (t * 1e9).toLong(), c(base.first, 0.25, 0.0), c(base.second, 1.0, 1.0), c(base.third, 0.6, 0.0),
                faceFound = faceEvery == 1 || k % faceEvery != 0, motion = motion,
            )
        }
        return out
    }

    @Test fun cleanPulseAtSeveralRates() {
        for (hr in listOf(55.0, 72.0, 90.0, 130.0)) {
            val r = FaceRppg.analyse(gen(hr, 20.0))
            assertTrue("hr $hr should be accepted: ${r.reasons} snr ${r.snrDb}", r.accepted)
            assertEquals("hr $hr", hr, r.hrBpm!!, 3.0)
        }
    }

    @Test fun brightnessWobbleThreeTimesThePulseDoesNotCreateAFalsePulse() {
        val r = FaceRppg.analyse(gen(72.0, 20.0, wobbleAmp = 0.01, wobbleHz = 1.7))
        assertTrue("accepted ${r.reasons} snr ${r.snrDb}", r.accepted)
        assertEquals(72.0, r.hrBpm!!, 3.0) // not 102, the wobble rate
    }

    @Test fun noiseOnlyIsNeverAPulse() {
        var accepted = 0
        for (seed in 1L..30L) if (FaceRppg.analyse(gen(null, 20.0, seed = seed, noise = 0.002)).accepted) accepted++
        assertEquals("no pulse present, none may be accepted", 0, accepted)
    }

    @Test fun wobbleOnlyIsNeverAPulse() {
        // pure brightness change with no pulse: POS must cancel it, and the gate must not accept what is left
        var accepted = 0
        for (seed in 1L..20L) if (FaceRppg.analyse(gen(null, 20.0, seed = seed, wobbleAmp = 0.02)).accepted) accepted++
        assertEquals(0, accepted)
    }

    @Test fun flatTraceIsRejected() {
        val flat = (0 until 600).map { FaceFrame(it * 33_333_333L, 180.0, 120.0, 100.0, true) }
        val r = FaceRppg.analyse(flat)
        assertFalse(r.accepted); assertNull(r.hrBpm)
    }

    @Test fun shortCaptureIsRejected() {
        val r = FaceRppg.analyse(gen(72.0, 6.0))
        assertFalse(r.accepted); assertTrue(FaceReason.TOO_SHORT in r.reasons)
    }

    @Test fun faceLostForHalfTheTimeIsRejected() {
        val r = FaceRppg.analyse(gen(72.0, 20.0, faceEvery = 2))
        assertFalse(r.accepted); assertTrue(FaceReason.NO_FACE in r.reasons)
    }

    @Test fun darkSkinWeakPulseIsRejectedOrRight_neverConfidentlyWrong() {
        for (seed in 1L..10L) {
            val r = FaceRppg.analyse(gen(72.0, 20.0, seed = seed, pulseAmp = 0.0008, base = Triple(95.0, 62.0, 50.0)))
            if (r.accepted) assertEquals("accepted wrong value, seed $seed", 72.0, r.hrBpm!!, 5.0)
        }
    }

    @Test fun tooDarkAndTooBrightAreNamed() {
        val dark = FaceRppg.analyse(gen(72.0, 20.0, base = Triple(30.0, 20.0, 18.0)))
        assertTrue(FaceReason.TOO_DARK in dark.reasons); assertFalse(dark.accepted)
        val bright = FaceRppg.analyse(gen(72.0, 20.0, base = Triple(250.0, 240.0, 236.0)))
        assertTrue(FaceReason.TOO_BRIGHT in bright.reasons); assertFalse(bright.accepted)
    }

    @Test fun movingHeadIsRejected() {
        val r = FaceRppg.analyse(gen(72.0, 20.0, motion = 0.08))
        assertFalse(r.accepted); assertTrue(FaceReason.MOVING in r.reasons)
    }

    @Test fun droppedFramesAreRejectedNotGuessed() {
        val r = FaceRppg.analyse(gen(72.0, 20.0, dropEvery = 5))
        assertFalse(r.accepted); assertTrue(FaceReason.UNEVEN_FRAMES in r.reasons)
    }

    @Test fun breathingNeedsALongWindow() {
        val long = FaceRppg.analyse(gen(72.0, 60.0, respHz = 0.25))
        assertTrue(long.accepted)
        assertNotNull(long.respPerMin)
        assertEquals(15.0, long.respPerMin!!, 2.0)
        val short = FaceRppg.analyse(gen(72.0, 20.0, respHz = 0.25))
        assertNull(short.respPerMin)
    }

    @Test fun confidenceStaysWellBelowTheFingertip() {
        val r = FaceRppg.analyse(gen(72.0, 20.0, noise = 0.0001))
        assertTrue(r.accepted)
        assertTrue("confidence ${r.confidence}", r.confidence <= 0.6)
    }

    @Test fun faceMustAgreeWithTheFingertipWhenOneExists() {
        assertTrue(FaceRppg.agreesWithFingertip(72.0, null))
        assertTrue(FaceRppg.agreesWithFingertip(72.0, 75.0))
        assertFalse(FaceRppg.agreesWithFingertip(72.0, 95.0))
    }
}
