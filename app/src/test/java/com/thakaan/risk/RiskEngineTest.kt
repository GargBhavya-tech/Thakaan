package com.thakaan.risk

import com.thakaan.core.Provenance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RiskEngineTest {
    private val MIN = 60_000_000_000L

    private fun exp(wbgt: Double? = 22.0, dose: Double = 0.0, debt: Double = 0.0, hour: Double = 9.0, accl: Int = 0, sun: Boolean? = false) =
        ExposureInputs(wbgt, dose, debt, hour, accl, sun)

    private fun body(psi: Double? = 0.5, age: Double = 60.0, q: Double = 0.9, core: Double? = null, restHr: Double? = null, restMin: Double = 0.0) =
        BodyInputs(hrAgeSec = age, hrQuality = q, psi = psi, coreTrendC = core, restHrBpm = restHr, restHrSustainedMin = restMin)

    private fun step(e: RiskEngine, t: Int, ex: ExposureInputs, b: BodyInputs = body(), brain: BrainInputs = BrainInputs(), sym: Set<Symptom> = emptySet(), prov: Provenance = Provenance.REAL, recovery: Boolean = false, collapse: Boolean = false) =
        e.update(RiskInputs(t * MIN, ex, b, brain, sym, collapse, recovery, prov))

    // ---------------------------------------------------------------- the two rides from ticket 39

    @Test fun aCoolMorningRideStaysCool() {
        val e = RiskEngine()
        for (m in 0 until 180) {
            val o = step(e, m, exp(wbgt = 22.0, dose = m * 0.1, debt = 0.0, hour = 8.0 + m / 60.0, sun = m % 10 < 3), body(psi = 0.4))
            assertEquals("minute $m tss=${o.tss}", Level.COOL, o.level)
        }
    }

    @Test fun aHotAfternoonWithoutDrinksClimbsToStrained() {
        val e = RiskEngine()
        var prev = Level.COOL
        var reached = Level.COOL
        for (m in 0 until 180) {
            val f = m / 179.0
            val o = step(e, m, exp(wbgt = 28.0 + 4.0 * f, dose = 120.0 * f, debt = 70.0 * f, hour = 12.0 + 3.0 * f, sun = true), body(psi = 1.0 + 3.0 * f))
            assertTrue("level fell during a monotone climb at minute $m", o.level >= prev)
            prev = o.level; reached = o.level
        }
        assertTrue("ended at $reached", reached >= Level.STRAINED)
    }

    // ---------------------------------------------------------------- stability

    private fun tssFor(wbgt: Double, psi: Double): Int = step(RiskEngine(), 0, exp(wbgt = wbgt, dose = 60.0, debt = 30.0, hour = 13.0, sun = true), body(psi = psi)).tss

    @Test fun levelsDoNotFlapAtABoundary() {
        // find an exposure where psi 4.0 gives a TSS just under 55 and psi 4.8 just over
        var wb = 24.0
        while (wb < 36.0 && tssFor(wb, 4.0) < 51) wb += 0.1
        val lo = tssFor(wb, 4.0); val hi = tssFor(wb, 4.8)
        assertTrue("setup: lo=$lo hi=$hi", lo in 51..54 && hi >= 55)

        val e = RiskEngine()
        var rawChanges = 0; var shownChanges = 0
        var lastRaw: Level? = null; var lastShown: Level? = null
        for (m in 0 until 120) {
            val o = step(e, m, exp(wbgt = wb, dose = 60.0, debt = 30.0, hour = 13.0, sun = true), body(psi = if (m % 2 == 0) 4.0 else 4.8))
            val raw = RiskEngine.levelForTss(o.tss)
            if (lastRaw != null && raw != lastRaw) rawChanges++
            if (lastShown != null && o.level != lastShown) shownChanges++
            lastRaw = raw; lastShown = o.level
        }
        assertTrue("the raw score really does cross the boundary (changes=$rawChanges)", rawChanges >= 20)
        assertTrue("shown level flapped $shownChanges times", shownChanges <= 2)
    }

    @Test fun dangerNeverClearsWithoutARecoveryCheck() {
        val e = RiskEngine()
        step(e, 0, exp(), body(), sym = setOf(Symptom.CONFUSION))
        assertEquals(Level.DANGER, e.level)
        for (m in 1..120) step(e, m, exp(), body(psi = 0.1)) // symptom gone, everything calm
        assertEquals("Danger must hold", Level.DANGER, e.level)
        step(e, 121, exp(), body(psi = 0.1), recovery = true)
        assertTrue("recovery steps down, not straight to Cool", e.level == Level.STRAINED)
        for (m in 122..160) step(e, m, exp(), body(psi = 0.1), recovery = true)
        assertTrue("later it can keep falling", e.level < Level.STRAINED)
    }

    // ---------------------------------------------------------------- anchors (core at 38.5 / 38.0)

    @Test fun coreAnchorsFireEarly() {
        fun lvl(core: Double, accl: Int) = step(RiskEngine(), 0, exp(accl = accl), body(core = core)).level
        assertTrue("unacclimatised 38.0", lvl(38.0, 0) >= Level.STRAINED)
        assertTrue("acclimatised 38.5", lvl(38.5, 10) >= Level.STRAINED)
        assertTrue("acclimatised 38.0 is not yet", lvl(38.0, 10) < Level.STRAINED)
        assertEquals(Level.DANGER, lvl(39.0, 10))
    }

    @Test fun anIndependentRestingHeartRateRuleBacksTheCoreEstimate() {
        // core estimate looks fine (it loses sensitivity near the danger zone) but resting HR stays above 100
        val high = step(RiskEngine(), 0, exp(), body(psi = 0.2, core = 37.2, restHr = 105.0, restMin = 6.0))
        assertTrue(high.level >= Level.STRAINED)
        val moderate = step(RiskEngine(), 0, exp(), body(psi = 0.2, core = 37.2, restHr = 93.0, restMin = 6.0))
        assertTrue(moderate.level >= Level.WARMING)
        val brief = step(RiskEngine(), 0, exp(), body(psi = 0.2, core = 37.2, restHr = 105.0, restMin = 1.0))
        assertEquals("a one-minute spike is not sustained", Level.COOL, brief.level)
    }

    // ---------------------------------------------------------------- brain, symptoms, collapse

    @Test fun brainFamiliesRaiseTheFloor() {
        val one = step(RiskEngine(), 0, exp(), body(psi = 0.1), BrainInputs(setOf(BrainFamily.TOUCH)))
        assertTrue(one.level < Level.STRAINED)
        val two = step(RiskEngine(), 0, exp(), body(psi = 0.1), BrainInputs(setOf(BrainFamily.TOUCH, BrainFamily.MOTION)))
        assertTrue(two.level >= Level.STRAINED)
        val three = step(RiskEngine(), 0, exp(), body(psi = 0.1), BrainInputs(setOf(BrainFamily.TOUCH, BrainFamily.MOTION, BrainFamily.VOICE)))
        assertEquals(Level.DANGER, three.level)
    }

    @Test fun symptomsAreWeighedByExposure() {
        val mild = step(RiskEngine(), 0, exp(), body(psi = 0.1), sym = setOf(Symptom.DIZZINESS))
        assertTrue("dizzy on a cool morning is not Strained", mild.level < Level.STRAINED)
        val hot = step(RiskEngine(), 0, exp(wbgt = 33.0, dose = 120.0, hour = 14.0, sun = true), body(psi = 0.1), sym = setOf(Symptom.DIZZINESS))
        assertTrue("dizzy in heavy exposure is", hot.level >= Level.STRAINED)
        assertEquals(Level.DANGER, step(RiskEngine(), 0, exp(), body(), sym = setOf(Symptom.CONFUSION)).level)
    }

    @Test fun collapseIsDangerAndFlagged() {
        val o = step(RiskEngine(), 0, exp(), body(), collapse = true)
        assertEquals(Level.DANGER, o.level)
        assertTrue(o.collapse)
    }

    // ---------------------------------------------------------------- explainability, provenance, bounds

    @Test fun everyLevelChangeRecordsItsTopThreeDrivers() {
        val e = RiskEngine()
        step(e, 0, exp(), body(psi = 0.1))
        val o = step(e, 1, exp(wbgt = 33.0, dose = 100.0, debt = 50.0, hour = 14.0, sun = true), body(psi = 5.5), BrainInputs(setOf(BrainFamily.TOUCH)))
        assertNotNull(o.changedFrom)
        assertTrue(o.drivers.size in 1..3)
        assertEquals(o.drivers.sortedByDescending { it.weight }.map { it.code }, o.drivers.map { it.code })
        assertTrue(o.drivers.all { it.text.isNotBlank() })
    }

    @Test fun noChangeMeansNoChangedFrom() {
        val e = RiskEngine()
        step(e, 0, exp(), body())
        assertNull(step(e, 1, exp(), body()).changedFrom)
    }

    @Test fun replayInputsNeverProduceARealLevel() {
        assertEquals(Provenance.REPLAY, step(RiskEngine(), 0, exp(), body(), prov = Provenance.REPLAY).provenance)
        assertEquals(Provenance.REAL, step(RiskEngine(), 0, exp(), body(), prov = Provenance.REAL).provenance)
    }

    @Test fun scoreAndBandStayInRange() {
        val rng = java.util.Random(5)
        repeat(500) {
            val o = step(RiskEngine(), 0, exp(wbgt = 15 + rng.nextDouble() * 25, dose = rng.nextDouble() * 300, debt = rng.nextDouble() * 120, hour = rng.nextDouble() * 24, sun = rng.nextBoolean()), body(psi = rng.nextDouble() * 10))
            assertTrue(o.tss in 0..100 && o.tssLow in 0..o.tss && o.tssHigh in o.tss..100)
        }
    }

    // ---------------------------------------------------------------- the five safety scenarios and their guard switches

    @Test fun allFiveSafetyChecksPass() {
        val r = SafetyChecks.runAll()
        assertEquals(5, r.size)
        r.forEach { assertTrue("check ${it.id} ${it.name}: ${it.detail}", it.passed) }
    }

    @Test fun eachGuardSwitchTurnsItsCheckRed() {
        fun failing(g: Guards) = SafetyChecks.runAll(g).filter { !it.passed }.map { it.id }
        assertTrue("hold", 1 in failing(Guards(holdWhenEvidenceStale = false)))
        assertTrue("floor", 2 in failing(Guards(exposureFloorOnStale = false)))
        assertTrue("shade", 3 in failing(Guards(unknownShadeIsSun = false)))
        assertTrue("brain", 4 in failing(Guards(brainFamiliesFloorStrained = false)))
        assertTrue("llm", 5 in failing(Guards(llmCannotChangeLevel = false)))
        assertFalse("the switches are independent", 5 in failing(Guards(holdWhenEvidenceStale = false)))
    }
}
