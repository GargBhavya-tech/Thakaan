package com.thakaan.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EcTempTest {
    private val vectorsJson by lazy { File("src/main/assets/ectemp_vectors.json").readText() }

    // ---------------------------------------------------------------- the 7 reference vectors (personalisation off)

    @Test fun allSevenReferenceCasesPass() {
        val r = EcTempVectors.check(vectorsJson)
        assertEquals("failures: ${r.failures}", "7/7", r.summary)
    }

    @Test fun theVectorCheckCanFail() {
        // Guard switch: a deliberately wrong Jacobian must turn the check red.
        val wrongSlope = object : ObservationModel {
            override fun h(ct: Double) = PublishedQuadratic.h(ct)
            override fun slope(ct: Double) = -PublishedQuadratic.slope(ct) // sign error
        }
        assertTrue(EcTempVectors.check(vectorsJson, model = wrongSlope).passed < 7)
    }

    // ---------------------------------------------------------------- published worked numbers

    @Test fun observationCurveAtStart() {
        assertEquals(83.08, PublishedQuadratic.h(37.1), 0.01)
        assertEquals(45.23, PublishedQuadratic.slope(37.1), 0.01)
    }

    @Test fun oneStepFromRestAt150() {
        val s = EcTemp.step(37.1, 0.02, 150.0)
        assertEquals(37.26, s.ct, 0.01)
        assertEquals(0.0183, s.v, 0.0002)
    }

    @Test fun steadyStateTable() {
        assertEquals(37.10, EcTemp.steadyStateCt(83.0)!!, 0.01)
        assertEquals(38.91, EcTemp.steadyStateCt(150.0)!!, 0.01)
        assertEquals(39.28, EcTemp.steadyStateCt(160.0)!!, 0.01)
        assertNull(EcTemp.steadyStateCt(200.0))
    }

    // ---------------------------------------------------------------- gaps: variance must grow, never shrink

    @Test fun missingMinuteIsPredictOnly() {
        val s = EcTemp.step(38.0, 0.01, null)
        assertEquals(38.0, s.ct, 0.0)
        assertEquals(0.01 + EcTempParams.GAMMA2, s.v, 1e-15)
    }

    @Test fun twelveMinuteGapGrowsVarianceLinearly() {
        val f = EcTempFilter()
        f.update(0, 120.0)
        val vBefore = f.v
        f.update(13 * 60L, null) // minutes 1..12 are the gap, minute 13 also has no reading
        // 13 predict-only minutes: v grows by exactly 13 * Q
        assertEquals(vBefore + 13 * EcTempParams.GAMMA2, f.v, 1e-12)
    }

    @Test fun gapNeverShrinksVarianceEvenWhenAReadingArrives() {
        val f = EcTempFilter()
        f.update(0, 100.0)
        // A reading right after a long gap can pull v down only via the update, but the predicted variance before it
        // must be larger than without the gap.
        val noGap = EcTempFilter().also { it.update(0, 100.0); it.update(60, 100.0) }
        val gap = EcTempFilter().also { it.update(0, 100.0); it.update(13 * 60L, 100.0) }
        assertTrue("variance after a gap must not be smaller", gap.v >= noGap.v)
    }

    // ---------------------------------------------------------------- the NIS gate

    @Test fun spikeAt160WhileResting_isRejected() {
        val model = Profile(ageYears = 35, lowestRestHr = 62.0).model()
        val f = EcTempFilter(model = model, gated = true)
        // settle at rest
        for (i in 0 until 40) f.update(i * 60L, 70.0)
        val ctBefore = f.ct
        f.update(40 * 60L, 160.0)
        assertEquals(false, f.lastAccepted)
        assertEquals("a rejected reading must not move the estimate", ctBefore, f.ct, 0.02)
    }

    @Test fun outOfRangeHeartRateIsTreatedAsMissing() {
        val r = EcTemp.stepGated(37.1, 0.02, 250.0, PublishedQuadratic)
        assertNull(r.accepted)
        assertEquals(0.02 + EcTempParams.GAMMA2, r.state.v, 1e-15)
    }

    // ---------------------------------------------------------------- properties

    @Test fun higherHeartRateNeverLowersTheEstimate() {
        val rng = java.util.Random(11)
        repeat(300) {
            val n = 1 + rng.nextInt(120)
            val base = List(n) { if (rng.nextInt(5) == 0) null else 40.0 + rng.nextDouble() * 150 }
            val raised = base.map { it?.let { hr -> minOf(hr + rng.nextDouble() * 20, 210.0) } }
            val lo = EcTemp.run(base)
            val hi = EcTemp.run(raised)
            lo.indices.forEach { i -> assertTrue("step $i", hi[i].ct >= lo[i].ct - 1e-12) }
        }
    }

    @Test fun sigmoidSlopeMatchesNumericDerivative() {
        val m = Profile(ageYears = 30, lowestRestHr = 60.0).model()
        for (ct in listOf(36.8, 37.5, 38.0, 38.5, 39.2)) {
            val eps = 1e-5
            val numeric = (m.h(ct + eps) - m.h(ct - eps)) / (2 * eps)
            assertEquals("ct=$ct", numeric, m.slope(ct), 1e-4)
            assertTrue(m.slope(ct) > 0)
        }
    }

    @Test fun sigmoidStaysBetweenFloorAndCeiling() {
        val m = Profile(ageYears = 40, lowestRestHr = 65.0).model()
        for (ct in listOf(30.0, 36.0, 37.0, 38.0, 40.0, 45.0)) {
            assertTrue(m.h(ct) in 65.0 - 1e-9..m.ceilingK + 1e-9)
        }
    }

    // ---------------------------------------------------------------- start-of-shift

    @Test fun circadianCurveHitsTheAnchorPoints() {
        assertEquals(36.8, ShiftStart.circadianCt(5.0), 1e-9)
        assertEquals(37.4, ShiftStart.circadianCt(17.0), 1e-9)
        assertTrue(ShiftStart.circadianCt(11.0) in 36.8..37.4)
    }

    @Test fun hotStartGetsLargerVariance() {
        assertEquals(0.02, ShiftStart.of(9.0, 80.0, 25.0).v0, 0.0)
        assertEquals(0.2, ShiftStart.of(9.0, 110.0, 25.0).v0, 0.0)
        assertEquals(0.2, ShiftStart.of(9.0, 80.0, 31.0).v0, 0.0)
        assertEquals(0.02, ShiftStart.of(9.0, null, null).v0, 0.0)
    }

    @Test fun processNoiseIsInflatedForTheFirst20Minutes() {
        val start = ShiftStart.of(9.0, null, null)
        val f = EcTempFilter(start = start)
        val before = f.v
        f.update(0, null)
        assertEquals(before + EcTempParams.GAMMA2 * 3.0, f.v, 1e-12)
        for (i in 1..25) f.update(i * 60L, null)
        val v26 = f.v
        f.update(26 * 60L, null)
        assertEquals(v26 + EcTempParams.GAMMA2, f.v, 1e-12)
    }

    @Test fun profileDerivesCeilingFromAge() {
        assertEquals(208 - 0.7 * 40, Profile(40).hrMax, 1e-12)
        assertFalse(Profile(40, 30.0).model().floorA < 40.0) // floor is clamped to a plausible range
    }
}
