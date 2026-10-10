package com.thakaan.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyExtrasTest {
    // ------------------------------------------------------------------ hydration (#25)

    private val body = Anthropometrics(70.0, 1.72)

    @Test fun threeHoursOfSunWithNoDrinksClimbsThenTwoDrinksBringItDown() {
        val l = HydrationLedger(70.0)
        repeat(180) { l.addMinute(sweatGPerM2h = 280.0, bsaM2 = body.bsa, metabolicW = 300.0) }
        val dry = l.band()
        assertTrue("deficit after 3 h without a drink: ${dry.text}", dry.midPct > 1.5 && dry.midPct < 6.0)
        assertTrue(dry.lowPct <= dry.midPct && dry.midPct <= dry.highPct)
        l.logDrink(500.0); l.logDrink(500.0)
        val wet = l.band()
        assertTrue("two drinks lower the deficit (${dry.midPct} -> ${wet.midPct})", wet.midPct < dry.midPct - 1.0)
    }

    @Test fun nudgeAsksAboutDrinksBeforeHydrationAloneEscalates() {
        val l = HydrationLedger(70.0)
        repeat(120) { l.addMinute(250.0, body.bsa, 250.0) }
        assertTrue(l.needsDrinkNudge())
        l.logDrink(400.0)
        assertFalse(l.needsDrinkNudge())
    }

    @Test fun anUnloggedDrinkMakesTheDeficitTooHighNeverTooLow() {
        // the engine and filter use mid; mid ignores the unlogged allowance, so a missed log errs towards early action
        val l = HydrationLedger(70.0)
        repeat(120) { l.addMinute(250.0, body.bsa, 250.0) }
        val b = l.band()
        assertTrue(b.lowPct < b.midPct)
    }

    @Test fun deficitFeedsTheObservationModelAndTheCorePrior() {
        val m = DehydratedModel(PublishedQuadratic, 2.0)
        assertEquals(PublishedQuadratic.h(37.5) + 9.0, m.h(37.5), 1e-9)
        assertEquals(PublishedQuadratic.slope(37.5), m.slope(37.5), 1e-12)
        assertEquals(0.175 * 1.5, hydrationCoreIncrement(1.0, 2.5), 1e-12)
        assertEquals(0.0, hydrationCoreIncrement(2.5, 1.0), 0.0) // rehydrating does not push the core estimate down
    }

    @Test fun drinkMustBePositive() {
        var threw = false
        try { HydrationLedger(70.0).logDrink(0.0) } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    // ------------------------------------------------------------------ excess HR and idle drift (#26)

    @Test fun excessHrIsMeasuredAgainstWorkloadAndPostureNotAgainstARestingBaseline() {
        val e = ExpectedHr(restSeatedHr = 70.0)
        val riding = e.excess(hr = 110.0, mets = 3.5, posture = BodyPosture.RIDING, secSinceStop = null, preStopHr = null, secSincePostureChange = null)
        assertEquals("expected for 3.5 METs riding is about 96", 96.0, riding.expectedBpm, 0.5)
        assertEquals(14.0, riding.excessBpm, 0.5)
        assertEquals(1.0, riding.weight, 0.0)
    }

    @Test fun readingsRightAfterDismountingAreDownWeightedAndUseTheStopTransient() {
        val e = ExpectedHr(70.0)
        val soon = e.excess(hr = 105.0, mets = 1.0, posture = BodyPosture.STANDING, secSinceStop = 10.0, preStopHr = 130.0, secSincePostureChange = 10.0)
        assertEquals(0.3, soon.weight, 0.0)
        // against a seated resting baseline this would look like +35 bpm of invented drift; against the transient it is small
        assertTrue("excess ${soon.excessBpm}", soon.excessBpm < 15.0)
        val later = e.excess(hr = 84.0, mets = 1.0, posture = BodyPosture.STANDING, secSinceStop = 240.0, preStopHr = 130.0, secSincePostureChange = 200.0)
        assertEquals(1.0, later.weight, 0.0)
    }

    @Test fun stopTemplateLearnsThisRidersRecoveryTime() {
        val t = StopTemplate(75.0)
        repeat(6) { // this rider recovers slowly: tau 150 s
            for (s in listOf(30.0, 60.0, 90.0, 120.0)) t.observe(s, 70.0 + 60.0 * Math.exp(-s / 150.0), 130.0, 70.0)
        }
        assertTrue("tau ${t.tauSec}", t.tauSec in 120.0..170.0)
    }

    @Test fun idleDriftOnlyComparesLikeWithLike() {
        val d = IdleDrift()
        d.recordMorning(BodyPosture.SEATED, 90.0, 68.0)
        assertEquals(10.0, d.drift(BodyPosture.SEATED, 100.0, 78.0)!!, 1e-9)
        assertNull("no morning value for another posture", d.drift(BodyPosture.STANDING, 100.0, 90.0))
        assertNull("no morning value for another time-since-stop bucket", d.drift(BodyPosture.SEATED, 10.0, 90.0))
    }

    // ------------------------------------------------------------------ sit-to-stand and perfusion (#27)

    private fun series(base: Double, rise: Double): List<Pair<Double, Double>> =
        (-30..-1).map { it.toDouble() to base } + (0..90).map { it.toDouble() to (if (it < 15) base + rise * it / 15.0 else base + rise) }

    @Test fun sitToStandRiseIsLargerAfterExertion() {
        val rest = SitToStand.analyse(series(68.0, 9.0), 0.9)
        val tired = SitToStand.analyse(series(84.0, 20.0), 0.9)
        assertEquals(9.0, rest.riseBpm!!, 0.5)
        assertTrue(tired.riseBpm!! > rest.riseBpm!! + 8)
        assertTrue(rest.flags.isEmpty())
    }

    @Test fun sitToStandFlagsPoorContactAndShortWindows() {
        val bad = SitToStand.analyse(series(68.0, 9.0), 0.3)
        assertTrue("low_contact_quality" in bad.flags)
        val short = SitToStand.analyse(listOf(-5.0 to 68.0, 40.0 to 80.0), 0.9)
        assertNull(short.riseBpm)
        assertTrue("short_baseline" in short.flags)
    }

    @Test fun perfusionCollapseNeedsHeatAndGoodContact() {
        val p = PerfusionCollapse()
        assertFalse(p.update(PerfusionReading(4.0, 0.9, true)))
        assertFalse(p.update(PerfusionReading(3.8, 0.9, true)))
        assertFalse(p.update(PerfusionReading(3.9, 0.9, true)))
        assertFalse("hard finger pressure gives a low PI but poor contact", p.update(PerfusionReading(0.8, 0.3, true)))
        assertTrue("sudden fall with good contact in heat", p.update(PerfusionReading(1.5, 0.9, true)))
        val cool = PerfusionCollapse()
        repeat(3) { cool.update(PerfusionReading(4.0, 0.9, true)) }
        assertFalse("not hot now", cool.update(PerfusionReading(1.5, 0.9, false)))
    }

    // ------------------------------------------------------------------ adaptive R (#29)

    @Test fun aSustainedOffsetRaisesRAndSlowsTheCoreTrendButAnchorsKeepThePlainEstimate() {
        val offsetHr = PublishedQuadratic.h(37.1) + 12.0
        val a = AdaptiveEcTemp()
        for (m in 0 until 45) a.update(m * 60L, offsetHr)
        assertTrue("R inflated at some point (${a.peakInflation})", a.peakInflation > 1.5)
        val plainRise = a.ctPlain - EcTempParams.CT0
        val adaptiveRise = a.ctTrend - EcTempParams.CT0
        assertTrue("adaptive rise $adaptiveRise vs plain $plainRise", adaptiveRise < 0.8 * plainRise)
        assertTrue("anchors never see a lower value than the plain filter", a.ctForAnchors >= a.ctPlain - 1e-12)
    }

    @Test fun withAdaptationOffTheTwoPathsAreIdentical() {
        val a = AdaptiveEcTemp(adaptive = false)
        for (m in 0 until 30) a.update(m * 60L, 100.0)
        assertEquals(a.ctPlain, a.ctTrend, 1e-12)
        assertEquals(1.0, a.rInflation, 1.0) // may be computed, but must not be applied
    }

    @Test fun noOffsetMeansNoInflation() {
        val a = AdaptiveEcTemp()
        for (m in 0 until 40) a.update(m * 60L, PublishedQuadratic.h(37.1) + (if (m % 2 == 0) 3.0 else -3.0))
        assertEquals(1.0, a.rInflation, 1e-12)
        assertNotNull(a.ctTrend)
    }
}

