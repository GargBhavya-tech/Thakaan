package com.thakaan.coach

import com.thakaan.core.Provenance
import com.thakaan.ppg.ThermalGuard
import com.thakaan.risk.BodyInputs
import com.thakaan.risk.ExposureInputs
import com.thakaan.risk.Level
import com.thakaan.risk.RiskEngine
import com.thakaan.risk.RiskInputs
import com.thakaan.risk.StrainForecast
import com.thakaan.risk.Symptom
import com.thakaan.risk.TssSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class CoachForecastGuardTest {
    private val MIN = 60_000_000_000L

    private fun good(hr: Double = 78.0) = RecoveryEvidence(hr, 0.9, 72.0, false, false, emptySet())

    // ------------------------------------------------------------------ cooling coach (#43)

    @Test fun coachRunsFourStepsOverTenMinutes() {
        val c = CoolingCoach()
        c.start(1000.0)
        assertEquals(600, c.totalSeconds)
        assertEquals(CoachStep.SHADE, c.currentStep(1010.0))
        assertEquals(CoachStep.DRINK, c.currentStep(1000.0 + 130))
        assertEquals(CoachStep.WET_CLOTH, c.currentStep(1000.0 + 250))
        assertEquals(CoachStep.FAN, c.currentStep(1000.0 + 450))
        assertNull(c.currentStep(1000.0 + 601))
    }

    @Test fun recheckCannotBeSkipped() {
        val c = CoolingCoach(); c.start(0.0)
        assertFalse(c.recheckAllowed(300.0))
        assertNull(c.submit(300.0, good()))
        assertFalse(c.recoveryPassed)
        assertTrue(c.recheckAllowed(601.0))
    }

    @Test fun missingOrBadEvidenceNeverPasses() {
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(null, 0.0, 72.0, false, false, emptySet())).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(78.0, 0.3, 72.0, false, false, emptySet())).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(95.0, 0.9, 72.0, false, false, emptySet())).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(78.0, 0.9, 72.0, null, false, emptySet())).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(78.0, 0.9, 72.0, false, true, emptySet())).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(78.0, 0.9, 72.0, false, false, setOf(Symptom.CONFUSION))).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(78.0, 0.9, 72.0, false, false, setOf(Symptom.THIRST, Symptom.HEADACHE))).passed)
        assertFalse(RecoveryCheck.evaluate(RecoveryEvidence(78.0, 0.9, 72.0, false, false, emptySet(), coreTrendC = 38.2)).passed)
        assertTrue(RecoveryCheck.evaluate(good()).passed)
        assertTrue("without a baseline an absolute 95 limit applies", RecoveryCheck.evaluate(RecoveryEvidence(88.0, 0.9, null, false, false, emptySet())).passed)
    }

    @Test fun aFailedRecheckAddsFiveMinutes() {
        val c = CoolingCoach(); c.start(0.0)
        val r = c.submit(601.0, RecoveryEvidence(110.0, 0.9, 72.0, false, false, emptySet()))!!
        assertFalse(r.passed)
        assertEquals(900, c.totalSeconds)
        assertFalse(c.recheckAllowed(700.0))
        assertTrue(c.recheckAllowed(901.0))
        assertTrue(c.submit(901.0, good())!!.passed)
        assertTrue(c.recoveryPassed)
    }

    @Test fun dangerStepsDownOnlyAfterTheCoachRecheckPasses() {
        val engine = RiskEngine()
        val ex = ExposureInputs(25.0, 0.0, 0.0, 9.0, 0, false)
        var t = 0L
        fun step(recovery: Boolean, sym: Set<Symptom> = emptySet()) = engine.update(RiskInputs(t, ex, BodyInputs(hrAgeSec = 30.0, hrQuality = 0.9, psi = 0.1), symptoms = sym, recoveryPassed = recovery, provenance = Provenance.REAL)).also { t += MIN }
        step(false, setOf(Symptom.CONFUSION))
        assertEquals(Level.DANGER, engine.level)
        val coach = CoolingCoach(); coach.start(0.0)
        repeat(12) { step(coach.recoveryPassed) } // ten minutes in the coach, symptom gone, recheck not yet done
        assertEquals("still Danger before the re-check", Level.DANGER, engine.level)
        assertTrue(coach.submit(601.0, good())!!.passed)
        step(coach.recoveryPassed)
        assertTrue("steps down after the passed re-check", engine.level < Level.DANGER)
    }

    // ------------------------------------------------------------------ forecast (#44)

    private fun climb(slopePerMin: Double, start: Int = 30, minutes: Int = 20): List<TssSample> =
        (0..minutes).map { TssSample(it.toDouble(), (start + slopePerMin * it).toInt()) }

    @Test fun aClimbingScoreForecastsStrainedWithinTheHour() {
        val f = StrainForecast.project(climb(1.0), plannedSunShare = 0.5)!!
        assertTrue("tss15 ${f.tss15}", f.tss15 in 55..80)
        assertTrue(f.tss15Low <= f.tss15 && f.tss15 <= f.tss15High)
        assertNotNull(f.minutesToStrained)
        assertTrue(f.headline!!.contains("Strained"))
    }

    @Test fun moreSunOnThePlannedRouteMakesItFasterNeverSlower() {
        val shade = StrainForecast.project(climb(0.8), 0.0)!!
        val sun = StrainForecast.project(climb(0.8), 1.0)!!
        assertTrue(sun.tss30 >= shade.tss30)
    }

    @Test fun aFallingScoreIsNeverExtrapolatedDownward() {
        val f = StrainForecast.project(climb(-0.8, start = 60), 0.0)!!
        assertEquals("held at the latest score (44)", 44, f.tss15)
        assertNull(f.minutesToStrained)
    }

    @Test fun tooLittleHistoryGivesNoForecast() {
        assertNull(StrainForecast.project(climb(1.0, minutes = 2), 0.5))
        assertNull(StrainForecast.project(emptyList(), 0.5))
    }

    @Test fun bandsStayInRange() {
        val f = StrainForecast.project(climb(3.0, start = 40), 1.0)!!
        assertTrue(f.tss30 <= 100 && f.tss30High <= 100 && f.tss30Low >= 0)
    }

    // ------------------------------------------------------------------ thermal guard (#12)

    @Test fun hotPhoneBlocksCapturesAndNamesTheFix() {
        assertFalse(ThermalGuard.captureAllowed(3, 0.5f).allowed)
        assertFalse(ThermalGuard.captureAllowed(4, null).allowed)
        assertTrue(ThermalGuard.captureAllowed(3, 0.5f).prompt!!.contains("Cool the phone"))
        assertFalse(ThermalGuard.captureAllowed(1, 0.97f).allowed)
        assertTrue(ThermalGuard.captureAllowed(0, 0.6f).allowed)
        assertTrue(ThermalGuard.captureAllowed(1, Float.NaN).allowed)
        assertNotNull(ThermalGuard.captureAllowed(2, 0.7f).prompt)
    }

    private fun redTrace(seconds: Int, fs: Int, dimAtSec: Int? = null, pulse: Boolean = true): DoubleArray =
        DoubleArray(seconds * fs) { i ->
            val base = if (dimAtSec != null && i >= dimAtSec * fs) 150.0 else 200.0
            base + if (pulse) 4.0 * sin(2 * PI * 1.2 * i / fs) else 0.0
        }

    @Test fun torchDimmingIsADcStepNotAPulse() {
        assertFalse("a normal pulse is not a dimming", ThermalGuard.torchDimmed(redTrace(20, 30), 30.0))
        assertTrue(ThermalGuard.torchDimmed(redTrace(20, 30, dimAtSec = 9), 30.0))
        assertFalse(ThermalGuard.torchDimmed(DoubleArray(10), 30.0))
    }
}

