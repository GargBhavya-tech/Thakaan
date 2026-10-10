package com.thakaan.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrainOutlookTest {
    private fun est(
        level: Level = Level.COOL, tss: Int = 30, low: Int = 25, high: Int = 35, slope: Double = 0.0,
        now: Double? = 29.0, soon: Double? = 29.0, pm: Pair<Double?, Double?> = 20.0 to 20.0, wind: Double? = 3.0,
    ) = StrainOutlook.estimate(level, tss, low, high, slope, now, soon, pm.first, pm.second, wind)

    @Test fun aSteadyScoreInCoolAirHasALowChance() {
        val o = est(tss = 20, low = 17, high = 23)
        assertTrue("chance ${o.strainedPct}", o.strainedPct < 5)
        assertEquals(OutlookBand.LOW, o.band)
        assertTrue(o.advice.contains("steady"))
    }

    @Test fun aRisingScoreRaisesTheChance() {
        assertTrue(est(slope = 1.0).strainedPct > est(slope = 0.0).strainedPct)
    }

    @Test fun risingHeatRaisesTheChanceAndIsNamed() {
        val hot = est(soon = 34.0)
        assertTrue(hot.strainedPct > est(soon = 29.0).strainedPct)
        assertTrue(hot.reasons.any { it.contains("Heat index rising") })
    }

    @Test fun unknownWeatherCountsAsHot() {
        val unknown = est(now = null, soon = null)
        assertTrue(unknown.strainedPct >= est().strainedPct)
        assertTrue(unknown.reasons.any { it.contains("Weather unknown") })
    }

    @Test fun aFallingScoreIsNeverAPromisedRecovery() {
        val fall = listOf(TssSample(0.0, 60), TssSample(5.0, 50), TssSample(10.0, 40), TssSample(15.0, 30), TssSample(20.0, 20))
        assertEquals(0.0, StrainOutlook.slopePerMin(fall), 0.0)
        assertEquals(est(slope = 0.0).strainedPct, est(slope = -2.0).strainedPct)
    }

    @Test fun aRisingHistoryHasAPositiveSlope() {
        val up = (0..10).map { TssSample(it.toDouble(), 20 + it) }
        assertEquals(1.0, StrainOutlook.slopePerMin(up), 0.01)
        assertEquals(0.0, StrainOutlook.slopePerMin(up.take(3)), 0.0) // too little history to claim a trend
    }

    @Test fun alreadyStrainedOrDangerIsTold() {
        val s = est(level = Level.STRAINED, tss = 60)
        assertEquals(100, s.strainedPct)
        assertTrue(s.advice.contains("Stop"))
        val d = est(level = Level.DANGER, tss = 85)
        assertEquals(100, d.strainedPct); assertEquals(100, d.dangerPct)
    }

    @Test fun risingFineDustAboveSatisfactoryAddsConcern() {
        assertTrue(est(pm = 40.0 to 90.0).strainedPct >= est(pm = 40.0 to 40.0).strainedPct)
        assertTrue(est(pm = 40.0 to 90.0).reasons.any { it.contains("Fine dust") })
        assertTrue("falling or low dust adds nothing", est(pm = 40.0 to 30.0).reasons.none { it.contains("Fine dust") })
    }

    @Test fun aWiderScoreBandRaisesTheChanceWhenTheMeanIsBelowTheLine() {
        assertTrue(est(low = 10, high = 50).strainedPct > est(low = 29, high = 31).strainedPct)
    }

    @Test fun aHighChanceTellsThemToRestAndDrink() {
        val o = est(tss = 50, low = 44, high = 56, slope = 0.5, soon = 33.0)
        assertEquals(OutlookBand.HIGH, o.band)
        assertTrue(o.advice.contains("5 minutes") && o.advice.contains("water"))
        assertTrue(o.strainedPct >= o.dangerPct)
    }

    @Test fun littleWindIsNamed() {
        assertTrue(est(wind = 0.5).reasons.any { it.contains("wind") })
        assertTrue(est(wind = 4.0).reasons.none { it.contains("wind") })
    }

    @Test fun theNormalCurveIsRight() {
        assertEquals(0.5, StrainOutlook.normalCdf(0.0), 1e-6)
        assertEquals(0.975, StrainOutlook.normalCdf(1.96), 1e-3)
        assertEquals(0.025, StrainOutlook.normalCdf(-1.96), 1e-3)
    }
}
