package com.thakaan.ppg

import com.thakaan.core.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

class QualityAndChecksTest {
    private fun ctx(hot: Boolean = false) = CheckContext(0L, "seated", 120, 300, hot, Provenance.REAL)

    private fun quality(spec: SyntheticPpg.Spec): QualityReport =
        Quality.evaluate(PpgPipeline.analyse(SyntheticPpg.generate(spec).frames))

    // ---------------------------------------------------------------- gates (ticket 10)

    @Test fun aGoodCapturePasses() {
        val q = quality(SyntheticPpg.Spec(durationSec = 20.0))
        assertTrue("reasons=${q.reasons} pi=${q.perfusionIndexPct} corr=${q.templateCorrelation} snr=${q.snrDb}", q.accepted)
        assertNull(q.prompt)
    }

    @Test fun pressingTooHardKillsPerfusionAndSaysPressLighter() {
        val q = quality(SyntheticPpg.Spec(durationSec = 20.0, piPercent = 0.12))
        assertFalse(q.accepted)
        assertTrue(Reason.LOW_PERFUSION in q.reasons)
        assertEquals(Reason.LOW_PERFUSION.prompt, q.prompt)
    }

    @Test fun aSweatyNoisyFingerIsRejectedWithWipePrompt() {
        val q = quality(SyntheticPpg.Spec(durationSec = 20.0, noiseFrac = 1.4, seed = 6))
        assertFalse(q.accepted)
        assertTrue("reasons=${q.reasons}", Reason.NOISY in q.reasons || Reason.MOTION in q.reasons)
    }

    @Test fun wigglingIsRejectedAsMotion() {
        val q = quality(SyntheticPpg.Spec(durationSec = 20.0, motion = listOf(Triple(3.0, 12.0, 5.0)), seed = 3))
        assertFalse(q.accepted)
        assertTrue("reasons=${q.reasons}", Reason.MOTION in q.reasons || Reason.NOISY in q.reasons || Reason.HR_DISAGREE in q.reasons)
    }

    @Test fun droppedFramesRejectTheWindow() {
        val q = quality(SyntheticPpg.Spec(durationSec = 20.0, dropAt = listOf(3.0, 6.0, 9.0, 12.0, 15.0)))
        assertTrue("reasons=${q.reasons}", Reason.DROPPED_FRAMES in q.reasons)
    }

    @Test fun aSaturatedLensSaysShadeThePhone() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(durationSec = 20.0, dc = 254.0, piPercent = 0.8))
        val frames = gen.frames.map { PpgFrame(it.tNanos, minOf(it.red, 255.0)) }
        val q = Quality.evaluate(PpgPipeline.analyse(frames))
        assertTrue("reasons=${q.reasons}", Reason.SATURATED in q.reasons)
        assertEquals(Reason.SATURATED.prompt, q.prompt)
    }

    @Test fun noFingerMeansNoNumber() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(durationSec = 20.0, dc = 8.0, piPercent = 3.0))
        val q = Quality.evaluate(PpgPipeline.analyse(gen.frames))
        assertTrue(Reason.NO_FINGER in q.reasons)
    }

    // ---------------------------------------------------------------- checks (ticket 11)

    @Test fun quickCheckDiscardsSettleAndReturnsHrOnly() {
        val frames = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = 82.0, durationSec = 37.0)).frames
        val r = Checks.evaluate(CheckKind.QUICK, frames, ctx())
        assertTrue("reasons=${r.reasons}", r.accepted)
        assertTrue(abs(r.hrBpm!! - 82.0) <= 3.0)
        assertNull("RMSSD must appear only for the full check", r.rmssdMs)
        assertEquals("seated", r.context.postureState)
        assertEquals(Provenance.REAL, r.context.provenance)
    }

    @Test fun fullCheckReturnsRmssdAndRespiration() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = 72.0, durationSec = 72.0, hrvSdMs = 35.0, respBpm = 14.0, seed = 21))
        val r = Checks.evaluate(CheckKind.FULL, gen.frames, ctx())
        assertTrue("reasons=${r.reasons}", r.accepted)
        assertNotNull(r.rmssdMs)
        assertTrue("hr=${r.hrBpm}", abs(r.hrBpm!! - 72.0) <= 3.0)
        assertNotNull(r.respirationBpm)
        assertTrue("resp=${r.respirationBpm}", abs(r.respirationBpm!! - 14.0) <= 3.0)
        assertTrue(r.perfusionIndexPct!! > 0.3)
        assertFalse(r.rmssdHeatAffected)
    }

    @Test fun rmssdTracksTheTruthAndIsFlaggedWhenHot() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = 70.0, durationSec = 72.0, hrvSdMs = 40.0, seed = 5))
        val r = Checks.evaluate(CheckKind.FULL, gen.frames, ctx(hot = true))
        assertTrue(r.accepted)
        assertTrue(r.rmssdHeatAffected)
        // truth from the generator's own intervals over the same span
        val t = gen.ibiMs
        var s = 0.0
        for (i in 1 until t.size - 2) s += (t[i] - t[i - 1]) * (t[i] - t[i - 1])
        val truth = sqrt(s / (t.size - 3))
        assertTrue("rmssd=${r.rmssdMs} truth=$truth", abs(r.rmssdMs!! - truth) < truth * 0.5)
    }

    @Test fun rmssdIsNeverComputedFromUnderAMinute() {
        val frames = SyntheticPpg.generate(SyntheticPpg.Spec(durationSec = 50.0)).frames // 10 settle + 40 s of data
        val r = Checks.evaluate(CheckKind.FULL, frames, ctx())
        assertFalse("a short full check must not return a result", r.accepted && r.rmssdMs != null)
        assertNull(r.rmssdMs)
    }

    @Test fun aBadCaptureNeverProducesANumber() {
        val frames = SyntheticPpg.generate(SyntheticPpg.Spec(durationSec = 40.0, piPercent = 0.1)).frames
        val r = Checks.evaluate(CheckKind.QUICK, frames, ctx())
        assertFalse(r.accepted)
        assertNull(r.hrBpm)
        assertNotNull(r.prompt)
    }
}
