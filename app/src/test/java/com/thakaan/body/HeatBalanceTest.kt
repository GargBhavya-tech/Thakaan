package com.thakaan.body

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeatBalanceTest {
    private val body = Anthropometrics(70.0, 1.72)

    private val hotRide = ThermalEnv(airC = 36.0, mrtC = 52.0, rhPercent = 40.0, airSpeedMs = 3.0)
    private val coolRide = ThermalEnv(airC = 24.0, mrtC = 25.0, rhPercent = 55.0, airSpeedMs = 3.0)
    private val shadeRest = ThermalEnv(airC = 30.0, mrtC = 30.0, rhPercent = 50.0, airSpeedMs = 0.3)

    private fun runFilter(env: ThermalEnv?, metW: Double?, minutes: Int, enabled: Boolean = true): EcTempFilter {
        val f = EcTempFilter()
        val prior = HeatBalancePrior(body, clo = 0.5, enabled = enabled)
        f.prior = { ct -> prior.incrementC(ct, env, metW) }
        for (m in 0 until minutes) f.update(m * 60L, null) // no heart rate at all
        return f
    }

    @Test fun hotRideRisesFasterThanCoolRideAndRestInShadeStaysFlat() {
        val hot = runFilter(hotRide, 330.0, 90).ct
        val cool = runFilter(coolRide, 330.0, 90).ct
        val rest = runFilter(shadeRest, 105.0, 90).ct
        println("DIAG hot=$hot cool=$cool rest=$rest")
        assertTrue("hot $hot vs cool $cool", hot > cool + 0.3)
        assertTrue("hot ride rises above start: $hot", hot > EcTempParams.CT0 + 0.4)
        assertTrue("resting in shade stays flat: $rest", kotlin.math.abs(rest - EcTempParams.CT0) < 0.25)
        assertTrue("never beyond a physiologic range: $hot", hot < 40.5)
    }

    @Test fun switchedOffOrMissingInputsMeanTheExactPlainFilter() {
        val plain = EcTempFilter(); for (m in 0 until 60) plain.update(m * 60L, null)
        assertEquals(plain.ct, runFilter(hotRide, 330.0, 60, enabled = false).ct, 0.0)
        assertEquals(plain.ct, runFilter(null, 330.0, 60).ct, 0.0)
        assertEquals(plain.ct, runFilter(hotRide, null, 60).ct, 0.0)
    }

    @Test fun priorNeverCoolsFasterThanItsSmallClamp() {
        val p = HeatBalancePrior(body)
        var worst = 0.0
        var ct = 38.0
        repeat(120) { val d = p.incrementC(ct, coolRide, 90.0); worst = minOf(worst, d); ct += d }
        assertTrue("cooling per minute $worst", worst >= -HeatBalancePrior.MAX_COOLING_PER_MIN - 1e-12)
    }

    @Test fun priorKeepsTheFilterVarianceGrowingAcrossTheGap() {
        val f = runFilter(hotRide, 330.0, 30)
        assertTrue(f.v > EcTempParams.V0)
    }

    @Test fun sweatRisesWithHeatLoadAndPhsIsCapped() {
        val p = HeatBalancePrior(body)
        p.incrementC(37.8, hotRide, 330.0); val hot = p.lastSweatGPerM2h
        val p2 = HeatBalancePrior(body)
        p2.incrementC(37.0, shadeRest, 105.0); val rest = p2.lastSweatGPerM2h
        assertTrue("sweat hot $hot vs rest $rest", hot > rest)
        val phsHot = Phs.sweatRate(hotRide, 330.0, 0.5, body, acclimatised = false)
        val phsCool = Phs.sweatRate(coolRide, 150.0, 0.5, body, acclimatised = false)
        assertTrue(phsHot.requiredSweatGPerH > phsCool.requiredSweatGPerH)
        val extreme = Phs.sweatRate(ThermalEnv(42.0, 60.0, 30.0, 1.0), 600.0, 0.5, body, false)
        assertTrue(extreme.capped)
        assertTrue("cap holds: ${extreme.requiredSweatGPerH}", extreme.requiredSweatGPerH < 1500.0)
        val accl = Phs.sweatRate(ThermalEnv(42.0, 60.0, 30.0, 1.0), 600.0, 0.5, body, true)
        assertTrue(accl.requiredSweatGPerH > extreme.requiredSweatGPerH)
    }

    @Test fun globeTemperatureConvertsToAHigherMeanRadiantTemperatureInSun() {
        val mrt = ThermalEnv.mrtFromGlobe(globeC = 52.0, airC = 34.0, airSpeedMs = 1.5)
        assertTrue("mrt $mrt", mrt > 52.0)
        assertEquals(34.0, ThermalEnv.mrtFromGlobe(34.0, 34.0, 1.5), 1e-6)
        assertTrue(ThermalEnv.mrtFromIrradiance(34.0, 900.0, 1.0) > ThermalEnv.mrtFromIrradiance(34.0, 900.0, 0.0))
    }

    @Test fun bodySurfaceAreaIsPlausible() {
        assertEquals(1.83, body.bsa, 0.05)
    }
}
