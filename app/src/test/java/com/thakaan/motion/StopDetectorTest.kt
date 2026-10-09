package com.thakaan.motion

import com.thakaan.core.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StopDetectorTest {
    private fun est(sec: Int, a: Activity, speed: Double?): ActivityEstimate =
        ActivityEstimate(sec * 1_000_000_000L, a, Placement.UNKNOWN, Metabolism.mets(a, speed), speed, 0.9, Provenance.REAL)

    private class Timeline(val d: StopDetector = StopDetector()) {
        val stamps = HashMap<Int, StateStamp>()
        fun run(from: Int, to: Int, a: Activity, speed: Double?, e: (Int, Activity, Double?) -> ActivityEstimate) {
            for (s in from until to) stamps[s] = d.update(e(s, a, speed))
        }
    }

    @Test fun rideStopRideDismountWalkRest_stateChangesLandWithin2Seconds() {
        val tl = Timeline()
        val e = { s: Int, a: Activity, sp: Double? -> est(s, a, sp) }
        tl.run(0, 60, Activity.RIDING, 8.0, e)       // riding
        tl.run(60, 90, Activity.IDLE, 0.0, e)        // red light stop at t=60
        tl.run(90, 150, Activity.RIDING, 8.0, e)     // riding again
        tl.run(150, 155, Activity.IDLE, 0.0, e)      // stop at t=150 (dismount)
        tl.run(155, 175, Activity.WALKING, 1.2, e)   // walk away
        tl.run(175, 300, Activity.IDLE, 0.0, e)      // sit and rest

        fun first(s: MoveState, after: Int) = (after until 300).first { tl.stamps[it]!!.state == s }

        assertEquals(MoveState.MOVING, tl.stamps[30]!!.state)
        assertTrue("stopping within 2 s of the stop", first(MoveState.STOPPING, 55) - 60 in 0..2)
        assertTrue("stopped within 3 s of the stop", first(MoveState.STOPPED, 55) - 60 in 0..3)
        assertEquals(MoveState.MOVING, tl.stamps[100]!!.state)
        assertTrue("walking within 4 s of starting to walk", first(MoveState.WALKING, 150) - 155 in 0..4)
        assertTrue("resting after a minute of stillness", first(MoveState.RESTING, 175) - 175 in 55..70)
    }

    @Test fun timeSinceStopClockStartsAtTheStopAndResetsOnTheNextStop() {
        val tl = Timeline()
        val e = { s: Int, a: Activity, sp: Double? -> est(s, a, sp) }
        tl.run(0, 60, Activity.RIDING, 8.0, e)
        tl.run(60, 100, Activity.IDLE, 0.0, e)
        assertNull("no clock while moving", tl.stamps[30]!!.secSinceStop)
        val at90 = tl.stamps[90]!!.secSinceStop!!
        assertTrue("clock at t=90 should be about 30, got $at90", at90 in 28..31)
        tl.run(100, 140, Activity.RIDING, 8.0, e)
        assertNull(tl.stamps[120]!!.secSinceStop)
        tl.run(140, 170, Activity.IDLE, 0.0, e)
        val at160 = tl.stamps[160]!!.secSinceStop!!
        assertTrue("clock reset by the new stop, got $at160", at160 in 18..21)
    }

    @Test fun postureClockMarksMountAndDismount() {
        val tl = Timeline()
        val e = { s: Int, a: Activity, sp: Double? -> est(s, a, sp) }
        tl.run(0, 60, Activity.RIDING, 8.0, e)
        tl.run(60, 120, Activity.IDLE, 0.0, e)
        assertEquals(PostureEvent.DISMOUNT, tl.d.lastPostureEvent)
        val sincePosture = tl.stamps[100]!!.secSincePostureChange!!
        assertTrue("posture clock about 40 s at t=100, got $sincePosture", sincePosture in 38..42)
        tl.run(120, 130, Activity.RIDING, 8.0, e)
        assertEquals(PostureEvent.MOUNT, tl.d.lastPostureEvent)
    }

    @Test fun aBriefSlowdownIsNotAStop() {
        val tl = Timeline()
        val e = { s: Int, a: Activity, sp: Double? -> est(s, a, sp) }
        tl.run(0, 30, Activity.RIDING, 8.0, e)
        tl.run(30, 31, Activity.IDLE, 0.0, e) // one second below threshold
        tl.run(31, 60, Activity.RIDING, 8.0, e)
        assertTrue(tl.stamps.values.none { it.state == MoveState.STOPPED })
    }

    @Test fun everyStampCarriesBothClocksAndWeightsHeartRateSensibly() {
        val tl = Timeline()
        val e = { s: Int, a: Activity, sp: Double? -> est(s, a, sp) }
        tl.run(0, 60, Activity.RIDING, 8.0, e)
        tl.run(60, 200, Activity.IDLE, 0.0, e)
        val justAfter = tl.stamps[65]!!
        assertNotNull(justAfter.secSinceStop); assertNotNull(justAfter.secSincePostureChange)
        assertTrue("first 30 s after a dismount are down-weighted", justAfter.hrWeight() < 1.0)
        assertEquals(1.0, tl.stamps[190]!!.hrWeight(), 0.0)
    }

    @Test fun capturePromptsOnlyWhenStopped() {
        val tl = Timeline()
        val e = { s: Int, a: Activity, sp: Double? -> est(s, a, sp) }
        tl.run(0, 30, Activity.RIDING, 8.0, e)
        assertFalse(tl.stamps[20]!!.capturePromptAllowed())
        tl.run(30, 90, Activity.IDLE, 0.0, e)
        assertTrue(tl.stamps[80]!!.capturePromptAllowed())
    }
}
