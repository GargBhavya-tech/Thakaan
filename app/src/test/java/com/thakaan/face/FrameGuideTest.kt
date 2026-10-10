package com.thakaan.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameGuideTest {
    private val SEC = 1_000_000_000L
    private fun box(cx: Double, cy: Double, w: Double) = FaceBox(cx - w / 2, cy - w / 2, cx + w / 2, cy + w / 2)

    @Test fun guidanceNamesTheProblem() {
        assertEquals(FrameState.NO_FACE, FrameGuideRules.guide(null).state)
        assertEquals(FrameState.OK, FrameGuideRules.guide(box(0.5, 0.5, 0.45)).state)
        assertEquals(FrameState.TOO_FAR, FrameGuideRules.guide(box(0.5, 0.5, 0.15)).state)
        assertEquals(FrameState.TOO_CLOSE, FrameGuideRules.guide(box(0.5, 0.5, 0.9)).state)
        assertEquals(FrameState.OFF_CENTRE, FrameGuideRules.guide(box(0.8, 0.5, 0.35)).state)
        assertEquals(FrameState.CUT_OFF, FrameGuideRules.guide(FaceBox(-0.05, 0.2, 0.4, 0.7)).state)
    }

    @Test fun offsetPointsTowardTheEdgeTheFaceIsNear() {
        val g = FrameGuideRules.guide(box(0.8, 0.3, 0.35))
        assertTrue(g.dx > 0.4); assertTrue(g.dy < -0.2)
    }

    @Test fun redLightGateOpensOnlyAfterASteadyFaceAtAKnownLowSpeed() {
        val g = StopCaptureGate()
        assertFalse(g.update(0, FrameState.OK, 0.0, true))            // just appeared
        assertFalse(g.update(1 * SEC, FrameState.OK, 0.0, true))
        assertTrue(g.update(2 * SEC, FrameState.OK, 0.0, true))       // steady for 2 s, stopped
    }

    @Test fun gateStaysClosedWhenSpeedIsUnknownOrTooHighOrPhoneIsHot() {
        fun open(speed: Double?, thermal: Boolean): Boolean {
            val g = StopCaptureGate()
            g.update(0, FrameState.OK, speed, thermal)
            return g.update(3 * SEC, FrameState.OK, speed, thermal)
        }
        assertFalse("unknown speed", open(null, true))
        assertFalse("moving", open(4.0, true))
        assertFalse("phone too hot", open(0.0, false))
        assertTrue(open(0.1, true))
    }

    @Test fun aLostOrBadFaceRestartsTheSteadyClock() {
        val g = StopCaptureGate()
        g.update(0, FrameState.OK, 0.0, true)
        g.update(1 * SEC, FrameState.NO_FACE, 0.0, true)
        assertFalse("the clock restarted", g.update(2 * SEC, FrameState.OK, 0.0, true))
        assertFalse(g.update(3 * SEC, FrameState.OK, 0.0, true))
        assertTrue(g.update(4 * SEC, FrameState.OK, 0.0, true))
    }
}
