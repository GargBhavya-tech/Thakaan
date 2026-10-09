package com.thakaan.exposure

import com.thakaan.core.Provenance
import com.thakaan.motion.MoveState
import com.thakaan.motion.StateStamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class RestAndSkyTest {
    private fun light(state: SunState, sun: Boolean) = LightEstimate(state, sun, 0.8, "t", null, true, Provenance.REAL)
    private val shade = light(SunState.SHADE, false)
    private val sunny = light(SunState.SUN, true)
    private val unknown = light(SunState.UNKNOWN, true)
    private fun stamp(s: MoveState) = StateStamp(s, 100, 100)

    private fun run(l: RestLedger, from: Int, secs: Int, s: MoveState, lt: LightEstimate) {
        for (i in 0 until secs) l.update((from + i) * 1_000_000_000L, stamp(s), lt)
    }

    // ---------------------------------------------------------------- E7 rest ledger

    @Test fun onlyAShadedStillBreakOverFiveMinutesCounts() {
        val l = RestLedger()
        run(l, 0, 240, MoveState.STOPPED, shade)
        assertFalse("4 minutes is not a rest", l.onRealRest)
        run(l, 240, 120, MoveState.STOPPED, shade)
        assertTrue("6 minutes in shade is", l.onRealRest)
        assertTrue(l.lastRestLengthSec >= 300.0)
    }

    @Test fun aLongRedLightInTheSunIsNotARest() {
        val l = RestLedger()
        run(l, 0, 900, MoveState.STOPPED, sunny)
        assertFalse(l.onRealRest)
        assertNull(l.lastRealRestEndNanos)
    }

    @Test fun unknownShadeNeverGrantsARest() {
        val l = RestLedger()
        run(l, 0, 900, MoveState.RESTING, unknown)
        assertFalse(l.onRealRest)
    }

    @Test fun movingResetsTheRestClock() {
        val l = RestLedger()
        run(l, 0, 200, MoveState.STOPPED, shade)
        run(l, 200, 30, MoveState.MOVING, shade)
        run(l, 230, 200, MoveState.STOPPED, shade)
        assertFalse("two 3-minute halves do not make a rest", l.onRealRest)
    }

    @Test fun restDebtBuildsAfterAWorkBlockWithoutRest() {
        val l = RestLedger()
        run(l, 0, 600, MoveState.MOVING, sunny) // first reading at t=0
        val at90min = 90 * 60 * 1_000_000_000L
        assertEquals(30.0, l.restDebtMinutes(at90min), 1e-6)
        assertEquals(0.0, l.restDebtMinutes(30 * 60 * 1_000_000_000L), 0.0)
    }

    @Test fun aRealRestClearsTheDebt() {
        val l = RestLedger()
        run(l, 0, 60, MoveState.MOVING, sunny)
        run(l, 60, 400, MoveState.RESTING, shade)
        val soon = 460 * 1_000_000_000L + 10 * 60 * 1_000_000_000L
        assertTrue(l.restDebtMinutes(soon) == 0.0)
        assertTrue(l.minutesSinceRest(soon) < 12.0)
    }

    // ---------------------------------------------------------------- E8 drink log

    @Test fun drinksAreLoggedAndSummed() {
        val d = DrinkLog()
        d.log(10, DrinkSource.TAP)
        d.log(20, DrinkSource.VOICE, 500.0)
        d.log(30, DrinkSource.WATER_STOP)
        assertEquals(1000.0, d.totalMl(), 0.0)
        assertEquals(750.0, d.mlSince(20), 0.0)
        assertEquals(30L, d.lastDrinkNanos())
    }

    @Test fun voicePhrasesMatchAcrossLanguages() {
        for (p in listOf("pani piya", "Paani Piya!", "पानी पिया", "neeru kudide", "ನೀರು ಕುಡಿದೆ", "thanni kudichen", "నీళ్ళు తాగాను", "I drank water")) {
            assertTrue("should match: $p", DrinkPhrases.matches(p))
        }
        assertFalse("negation must not log a drink", DrinkPhrases.matches("pani nahi piya"))
        assertFalse(DrinkPhrases.matches("open the map"))
    }

    // ---------------------------------------------------------------- E12 to E14 sky view

    private fun expected(el: Double) = 30.0 + 15.0 * Math.sin(Math.toRadians(el))

    /** 14 satellites spread over the sky; [dip] gives extra loss in dB for a satellite (index, az, el). */
    private fun sky(dip: (Double, Double) -> Double = { _, _ -> 0.0 }, noise: Double = 0.0, rng: Random = Random(2)): List<SatObs> {
        val pos = listOf(
            0.0 to 25.0, 35.0 to 55.0, 70.0 to 30.0, 100.0 to 70.0, 135.0 to 45.0, 160.0 to 25.0, 170.0 to 55.0,
            190.0 to 65.0, 215.0 to 35.0, 250.0 to 50.0, 280.0 to 25.0, 310.0 to 60.0, 340.0 to 40.0, 20.0 to 75.0,
        )
        return pos.mapIndexed { i, (az, el) -> SatObs(i + 1, 1, expected(el) - dip(az, el) + rng.nextGaussian() * noise, el, az) }
    }

    private val sunHighSouth = Sun(180.0, 60.0)

    private fun settle(est: SkyViewEstimator, sats: () -> List<SatObs>, heading: Double? = null, sun: Sun? = sunHighSouth, n: Int = 12): SkyViewState {
        var s: SkyViewState? = null
        repeat(n) { s = est.update(sats(), heading, sun) }
        return s!!
    }

    @Test fun openSkyHasAHighSkyViewFactorAndTheSunIsNotBlocked() {
        val s = settle(SkyViewEstimator(), { sky() })
        assertTrue("svf=${s.skyViewFactor}", s.skyViewFactor!! > 0.85)
        assertEquals(false, s.sunBlocked)
        assertEquals(ShadeType.OPEN, s.shadeType)
    }

    @Test fun anUrbanCanyonBlocksTheSouthernSkyAndTheSun() {
        val s = settle(SkyViewEstimator(), { sky(dip = { az, _ -> if (az in 90.0..270.0) 22.0 else 0.0 }) })
        assertTrue("svf=${s.skyViewFactor}", s.skyViewFactor!! < 0.65)
        assertEquals(true, s.sunBlocked)
    }

    @Test fun theRidersBodyIsNotMistakenForShade() {
        // heading north: satellites behind the rider (az 150..210) lose 10 dB, the rest of the sky is open
        val est = SkyViewEstimator()
        val withBody = { sky(dip = { az, _ -> if (az in 130.0..230.0) 10.0 else 0.0 }) }
        val s = settle(est, withBody, heading = 0.0, sun = Sun(175.0, 50.0), n = 40)
        assertTrue("svf=${s.skyViewFactor}", s.skyViewFactor!! >= 0.8)
        assertEquals("body shadow must not read as the sun being blocked", false, s.sunBlocked)
    }

    @Test fun tooFewSatellitesMeansUnknownNotShade() {
        val s = SkyViewEstimator().update(sky().take(3), null, sunHighSouth)
        assertNull(s.skyViewFactor)
        assertNull(s.sunBlocked)
    }

    @Test fun treeCanopyIsDistinguishedFromAStructure() {
        val rng = Random(7)
        val canopy = settle(SkyViewEstimator(), { sky(dip = { _, _ -> 8.0 }, noise = 3.5, rng = rng) })
        assertEquals(ShadeType.TREE_CANOPY, canopy.shadeType)
        val structure = settle(SkyViewEstimator(), { sky(dip = { _, _ -> 20.0 }) })
        assertEquals(ShadeType.STRUCTURE, structure.shadeType)
        assertNotNull(structure.skyViewFactor)
    }
}
