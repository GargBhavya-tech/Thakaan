package com.thakaan.motion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CollapseTest {
    private val upright = doubleArrayOf(0.0, 1.0, 0.0)
    private val lying = doubleArrayOf(1.0, 0.0, 0.0)

    private fun run(b: ImuBuilder, riding: Boolean = false): CollapseEvent? {
        val d = CollapseDetector()
        d.setRiding(riding)
        for (s in b.samples) d.onAccel(s.tNanos, s.ax, s.ay, s.az)?.let { return it }
        return null
    }

    @Test fun fallOntoAMatThenStillnessIsDetected() {
        val ev = run(ImuBuilder().walking(10.0).fall(lying).idle(25.0, noise = 0.01))
        assertNotNull(ev)
        assertEquals(CollapseKind.FALL, ev!!.kind)
    }

    @Test fun slowSlumpToTheFloorThenStillnessIsDetected() {
        val ev = run(ImuBuilder().walking(10.0).rotateGravity(3.0, lying, jerk = 0.05).idle(25.0, noise = 0.01))
        assertNotNull("a slump has no impact but must still be caught", ev)
        assertEquals(CollapseKind.SLUMP, ev!!.kind)
    }

    @Test fun normalSittingDownDoesNotTrigger() {
        // same change of orientation, but the person fidgets and moves afterwards
        val ev = run(ImuBuilder().walking(10.0).rotateGravity(2.0, lying, jerk = 0.02).idle(25.0, noise = 0.2))
        assertNull(ev)
    }

    @Test fun walkingAndRidingBumpsDoNotTrigger() {
        assertNull(run(ImuBuilder().walking(90.0)))
        assertNull(run(ImuBuilder().riding(60.0), riding = true))
    }

    @Test fun getUpQuicklyAfterAFallCancelsIt() {
        val ev = run(ImuBuilder().walking(8.0).fall(lying).idle(3.0, noise = 0.01).walking(20.0))
        assertNull(ev)
    }

    @Test fun bikeTipOverWhileRidingIsDetected() {
        val ev = run(ImuBuilder().riding(10.0).fall(lying, impact = 32.0).idle(25.0, noise = 0.01), riding = true)
        assertNotNull(ev)
        assertEquals(CollapseKind.TIP_OVER, ev!!.kind)
    }

    // ---------------------------------------------------------------- the countdown always runs first

    @Test fun alarmOnlyAfterTheCountdownTimesOut() {
        val c = CountdownMachine(15_000)
        assertFalse(c.tick(1_000_000)) // idle: nothing happens
        assertEquals(CountdownState.IDLE, c.state)
        c.start(0, CollapseKind.SLUMP)
        assertEquals(CountdownState.COUNTDOWN, c.state)
        assertFalse(c.tick(14_000))
        assertEquals(15, c.secondsLeft(0))
        assertTrue(c.tick(15_000))
        assertEquals(CountdownState.ALARM, c.state)
    }

    @Test fun haanOrTapCancelsTheCountdown() {
        val c = CountdownMachine(15_000)
        c.start(0, CollapseKind.FALL)
        c.confirmSafe()
        assertEquals(CountdownState.IDLE, c.state)
        assertFalse(c.tick(60_000))
    }

    @Test fun alarmIsNotReachableWithoutTheCountdown() {
        val c = CountdownMachine(15_000)
        c.confirmSafe(); c.resolve()
        repeat(100) { assertFalse(c.tick(it * 1000L)) }
        assertEquals(CountdownState.IDLE, c.state)
    }

    @Test fun smsIsAPreviewWithLocation() {
        val sms = SmsPreview.of("Ravi", CollapseKind.SLUMP, 12.9716, 77.5946, listOf("+911234567890"))
        assertTrue(sms.body.contains("maps.google.com/?q=12.97160,77.59460"))
        assertTrue(sms.body.contains("collapsed from heat"))
        assertEquals(listOf("+911234567890"), sms.toNumbers)
        assertTrue(SmsPreview.of("Ravi", CollapseKind.FALL, null, null, emptyList()).body.contains("location unknown"))
    }
}
