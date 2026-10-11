package com.thakaan.ride

import com.thakaan.face.FatigueReason
import com.thakaan.face.FatigueSummary
import com.thakaan.risk.Level
import com.thakaan.risk.Symptom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RideRulesTest {
    private fun fatigue(perclos: Double = 0.03, closures: Int = 0, yawns: Int = 0) = FatigueSummary(true, null, 35.0, 14.0, perclos, closures, yawns, 0.0, 0.1)
    private val unreadable = FatigueSummary(false, FatigueReason.NO_FACE, 0.0, 0.0, 0.0, 0, 0, 0.0, 0.0)

    // ---------------------------------------------------------------- camera policy
    private fun run(p: RideCameraPolicy, from: Int, to: Int, speed: Double? = 8.0, dist: Double? = 2000.0, hot: Boolean = false, drowsy: Boolean = false): List<Boolean> =
        (from until to).map { p.decide(it.toDouble(), speed, dist, hot, drowsy).on }

    @Test fun theCameraWaitsForSteadyRidingThenWatchesForOneWindow() {
        val p = RideCameraPolicy()
        val seq = run(p, 0, 60)
        assertFalse("not in the first seconds", seq[3])
        assertTrue("on after 8 s of riding", seq[10])
        assertTrue(seq[45])
        assertFalse("off once the 42 s window is over", seq[55])
    }

    @Test fun itRestsEightySecondsThenWatchesAgain() {
        val p = RideCameraPolicy()
        val seq = run(p, 0, 260)
        val ons = seq.withIndex().filter { it.value }.map { it.index }
        assertTrue("first window around 8 to 50 s", ons.first() in 8..9)
        val secondStart = ons.first { it > 60 }
        assertTrue("second window starts about 80 s after the first ended, got $secondStart", secondStart in 125..135)
    }

    @Test fun aDrowsyWindowShortensTheRestToThirtySeconds() {
        val p = RideCameraPolicy()
        val seq = run(p, 0, 140, drowsy = true)
        val ons = seq.withIndex().filter { it.value }.map { it.index }
        val secondStart = ons.first { it > 60 }
        assertTrue("second window starts about 30 s after the first ended, got $secondStart", secondStart in 78..90)
    }

    @Test fun stoppingSwitchesTheCameraOffAndShortStopsDoNot() {
        val p = RideCameraPolicy()
        run(p, 0, 20)
        assertTrue("a 3 s pause at a light keeps it on", (20 until 23).map { p.decide(it.toDouble(), 0.0, 2000.0, false, false).on }.last())
        val d = (23 until 40).map { p.decide(it.toDouble(), 0.0, 2000.0, false, false) }.last()
        assertFalse(d.on); assertTrue(d.reason.contains("stopped"))
    }

    @Test fun neverNearTheDropPointNeverOnAHotPhone() {
        val p = RideCameraPolicy()
        assertTrue(run(p, 0, 30).last())
        val near = p.decide(31.0, 8.0, 100.0, false, false)
        assertFalse(near.on); assertTrue(near.reason.contains("drop point"))
        val hot = RideCameraPolicy().decide(10.0, 8.0, 2000.0, true, false)
        assertFalse(hot.on); assertTrue(hot.reason.contains("hot"))
    }

    @Test fun withNoSpeedReadingTheCameraStaysOffAndSaysWhy() {
        val p = RideCameraPolicy()
        assertTrue("never on without a speed", run(p, 0, 120, speed = null).none { it })
        assertTrue(p.decide(121.0, null, 2000.0, false, false).reason.contains("waiting for speed"))
        // and when the speed arrives it still waits for steady riding before the first window
        val after = (122 until 140).map { p.decide(it.toDouble(), 8.0, 2000.0, false, false).on }
        assertFalse(after[3]); assertTrue(after.last())
    }

    @Test fun theCameraIsOnLessThanHalfTheTimeWhileRiding() {
        val seq = run(RideCameraPolicy(), 0, 1200)
        val share = seq.count { it }.toDouble() / seq.size
        assertTrue("duty ${"%.2f".format(share)}", share in 0.25..0.40)
    }

    // ---------------------------------------------------------------- fitness
    @Test fun aReadableRestedRiderIsFit() {
        val f = RideFitness.assess(Level.COOL, fatigue(), emptySet())
        assertEquals(Verdict.FIT, f.verdict)
        assertEquals(Verdict.FIT, RideFitness.assess(Level.WARMING, fatigue(), emptySet()).verdict)
        assertTrue(RideFitness.assess(Level.WARMING, fatigue(), emptySet()).detail.contains("water"))
    }

    @Test fun anUnreadableFaceIsNeverFit() {
        Level.entries.forEach { l ->
            assertNotEquals("level $l", Verdict.FIT, RideFitness.assess(l, null, emptySet()).verdict)
            assertNotEquals("level $l", Verdict.FIT, RideFitness.assess(l, unreadable, emptySet()).verdict)
        }
        assertEquals(Verdict.RETRY, RideFitness.assess(Level.COOL, unreadable, null).verdict)
    }

    @Test fun strainedAndDangerRestFirst() {
        val s = RideFitness.assess(Level.STRAINED, fatigue(), emptySet())
        assertEquals(Verdict.REST, s.verdict); assertEquals(5, s.restMinutes); assertTrue(s.detail.contains("water"))
        val d = RideFitness.assess(Level.DANGER, fatigue(), emptySet())
        assertEquals(Verdict.REST, d.verdict); assertEquals(10, d.restMinutes)
    }

    @Test fun tiredSignsSendTheRiderToRest() {
        assertEquals(Verdict.REST, RideFitness.assess(Level.COOL, fatigue(perclos = 0.2), emptySet()).verdict)
        assertEquals(Verdict.REST, RideFitness.assess(Level.COOL, fatigue(closures = 2), emptySet()).verdict)
        assertEquals(Verdict.REST, RideFitness.assess(Level.COOL, fatigue(yawns = 2), emptySet()).verdict)
        assertEquals(Verdict.FIT, RideFitness.assess(Level.COOL, fatigue(closures = 1, yawns = 1), emptySet()).verdict)
    }

    @Test fun anyReportedSymptomSendsTheRiderToRest() {
        val f = RideFitness.assess(Level.COOL, fatigue(), setOf(Symptom.THIRST))
        assertEquals(Verdict.REST, f.verdict); assertEquals(5, f.restMinutes)
    }

    @Test fun drowsyIsFalseForAnUnreadableWindow() {
        assertFalse(RideFitness.drowsy(unreadable))
        assertTrue(RideFitness.drowsy(fatigue(perclos = 0.5)))
    }
}
