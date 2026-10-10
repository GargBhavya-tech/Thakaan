package com.thakaan.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.exp

class ForeheadThermalTest {
    /** A synthetic press: 10 s of rest with no touch, then the sensor rises from [start] toward start+[rise] with time constant [tau]. */
    private fun press(
        start: Double = 29.0, rise: Double = 4.0, tau: Double = 40.0, seconds: Int = 90, noise: Double = 0.02, seed: Long = 1,
        contactLost: IntRange? = null, restDrift: Double = 0.0, restSeconds: Int = 10,
    ): List<ThermalSample> {
        val rnd = Random(seed)
        val out = ArrayList<ThermalSample>()
        for (s in 0 until restSeconds) out += ThermalSample(s * 1_000_000_000L, start + restDrift * s + noise * rnd.nextGaussian(), 0.0)
        val base = start + restDrift * restSeconds
        for (s in 0..seconds) {
            val y = base + rise * (1 - exp(-s / tau)) + noise * rnd.nextGaussian()
            val lost = contactLost != null && s in contactLost
            out += ThermalSample((restSeconds + s) * 1_000_000_000L, y, if (lost) 0.0 else 1.0)
        }
        return out
    }

    private fun baseline(vararg rises: Double, start: Double = 29.0): ForeheadBaseline {
        var b = ForeheadBaseline()
        rises.forEach { r -> b = b.with(ForeheadFit(start, start + r, r, 40.0, 0.02)) }
        return b
    }

    @Test fun recoversTheCurveFromANoisyPress() {
        val r = ForeheadThermal.analyse(press())
        val fit = r.fit!!
        assertEquals(4.0, fit.riseC, 0.4)
        assertEquals(40.0, fit.tauSec, 14.0)
        assertTrue(fit.rmseC < ForeheadThermal.MAX_RMSE_C)
        assertEquals(ForeheadVerdict.NO_BASELINE, r.verdict)
    }

    @Test fun usualPressIsUsualAndAClearlyWarmerOneIsFlagged() {
        val b = baseline(3.9, 4.0, 4.1, 4.0, 3.95)
        assertEquals(ForeheadVerdict.USUAL, ForeheadThermal.analyse(press(rise = 4.1), b).verdict)
        assertEquals(ForeheadVerdict.WARMER, ForeheadThermal.analyse(press(rise = 5.4), b).verdict)
    }

    @Test fun aQuietBaselineCannotTurnNoiseIntoAWarmFlag() {
        // baseline almost identical: the spread floor (0.2) stops a 0.25 difference from counting as a fever
        val b = baseline(4.00, 4.01, 3.99, 4.00, 4.00)
        assertEquals(ForeheadVerdict.USUAL, ForeheadThermal.analyse(press(rise = 4.25), b).verdict)
    }

    @Test fun tooFewBaselinePressesMeansNoJudgement() {
        val r = ForeheadThermal.analyse(press(rise = 6.0), baseline(4.0, 4.1))
        assertEquals(ForeheadVerdict.NO_BASELINE, r.verdict)
    }

    @Test fun aDifferentStartingTemperatureIsNotCompared() {
        val b = baseline(4.0, 4.1, 3.9, 4.0, start = 29.0)
        assertEquals(ForeheadVerdict.NOT_COMPARABLE, ForeheadThermal.analyse(press(start = 25.0, rise = 6.0), b).verdict)
    }

    @Test fun badPressesAreNotUsableAndNeverLookNormal() {
        val b = baseline(4.0, 4.1, 3.9, 4.0)
        val cases = mapOf(
            "warm phone" to (press(start = 34.0) to ForeheadReason.PHONE_WARM),
            "drifting phone" to (press(restDrift = 0.1) to ForeheadReason.PHONE_DRIFTING),
            "no rest phase" to (press(restSeconds = 2) to ForeheadReason.NO_REST_PHASE),
            "no heat" to (press(rise = 0.0) to ForeheadReason.NO_HEAT_SEEN),
            "contact lost" to (press(contactLost = 30..55) to ForeheadReason.CONTACT_LOST),
            "short" to (press(seconds = 40) to ForeheadReason.TOO_SHORT),
        )
        for ((name, c) in cases) {
            val r = ForeheadThermal.analyse(c.first, b)
            assertEquals(name, ForeheadVerdict.NOT_USABLE, r.verdict)
            assertEquals(name, c.second, r.reason)
        }
    }

    @Test fun aVeryNoisySensorIsNotUsableWhicheverCheckCatchesItFirst() {
        val r = ForeheadThermal.analyse(press(noise = 0.4), baseline(4.0, 4.1, 3.9, 4.0))
        assertEquals(ForeheadVerdict.NOT_USABLE, r.verdict)
        assertTrue(r.reason == ForeheadReason.BAD_FIT || r.reason == ForeheadReason.PHONE_DRIFTING)
    }

    @Test fun nothingTheCheckSaysContainsATemperatureOrAnyNumber() {
        val b = baseline(4.0, 4.1, 3.9, 4.0)
        val results = listOf(
            ForeheadThermal.analyse(press(rise = 4.0), b), ForeheadThermal.analyse(press(rise = 5.5), b), ForeheadThermal.analyse(press(), ForeheadBaseline()),
            ForeheadThermal.analyse(press(start = 25.0), b), ForeheadThermal.analyse(press(start = 34.0), b), ForeheadThermal.analyse(press(rise = 0.0), b),
            ForeheadThermal.analyse(press(seconds = 10), b),
        )
        results.forEach { r ->
            // the only digits allowed are the "90 seconds" instruction, which is a duration, not a temperature
            assertFalse("message: ${r.message}", r.message.replace("90 seconds", "").any { it.isDigit() })
        }
        assertNotNull(results.first().message)
    }

    @Test fun baselineAccumulates() {
        var b = ForeheadBaseline()
        repeat(4) { b = b.with(ForeheadThermal.analyse(press(seed = 10L + it)).fit!!) }
        assertEquals(4, b.size)
    }
}
