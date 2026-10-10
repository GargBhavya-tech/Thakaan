package com.thakaan.shift

import com.thakaan.brain.FittsResult
import com.thakaan.brain.TremorResult
import com.thakaan.core.Provenance
import com.thakaan.ppg.CheckContext
import com.thakaan.ppg.CheckKind
import com.thakaan.ppg.CheckResult
import com.thakaan.risk.Symptom
import com.thakaan.settings.WorkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickCheckPlanTest {
    private val ctx = CheckContext(0, "standing", null, null, false, Provenance.REAL)
    private fun heart(ok: Boolean) = CheckResult(CheckKind.QUICK, ok, emptyList(), if (ok) null else "Keep your finger still", if (ok) 80.0 else null, null, false, null, null, null, 15.0, ctx)
    private fun tremor(valid: Boolean, lift: Boolean = false) = TremorResult(0.1, 0.2, 12.0, lift, valid, if (valid) "" else "needs at least 10 s of holding still")
    private fun fitts(valid: Boolean) = FittsResult(3.0, 0.1, 0.3, 40, valid, if (valid) "" else "too few taps")

    @Test fun theGeneralCheckStaysWithinTwoAndAHalfMinutes() {
        val p = QuickCheckPlan()
        assertTrue("total ${p.totalSeconds}s (the face check added 40 s to the original 90 s goal)", p.totalSeconds <= 130)
        assertFalse(CheckStepId.SPEECH in p.steps)
    }

    @Test fun speechStepOnlyWhenARecogniserExists() {
        val p = QuickCheckPlan(speechAvailable = true)
        assertTrue(CheckStepId.SPEECH in p.steps)
        // the fingertip step grew to 35 s (the pulse must be checked for steadiness); a speech step will need trimming elsewhere to keep 90 s
        assertTrue("with speech ${p.totalSeconds}s", p.totalSeconds <= 145)
    }

    @Test fun stepsRunInOrderAndEnd() {
        val p = QuickCheckPlan()
        var s = p.next(null)
        val seen = ArrayList<CheckStepId>()
        while (s != null) { seen += s; s = p.next(s) }
        assertEquals(p.steps, seen)
        assertNull(p.next(CheckStepId.SYMPTOMS))
    }

    @Test fun failedStepIsNotCompleteAndSaysWhatToDo() {
        val p = QuickCheckPlan()
        val o = QuickCheckOutcome()
        o.heart = heart(false)
        assertFalse(o.done(CheckStepId.FINGERTIP))
        assertEquals("Keep your finger still", o.advice(CheckStepId.FINGERTIP))
        o.tremor = tremor(false); o.fitts = fitts(false); o.symptoms = emptySet()
        assertFalse(o.isComplete(p))
        assertTrue(o.advice(CheckStepId.TAPPING).contains("few taps"))
    }

    @Test fun skippingIsRecordedNeverSilent() {
        val p = QuickCheckPlan()
        val o = QuickCheckOutcome()
        o.heart = heart(true); o.tremor = tremor(true); o.symptoms = setOf(Symptom.DIZZINESS)
        o.skipped[CheckStepId.TAPPING] = "worker skipped"
        assertFalse("the face step is still neither done nor skipped", o.isComplete(p))
        o.faceDone = true
        assertTrue(o.isComplete(p))
        assertTrue(o.summary().any { it.contains("skipped") && it.contains("worker skipped") })
    }

    @Test fun liftingConfoundIsNotedNotScored() {
        val o = QuickCheckOutcome()
        o.tremor = tremor(true, lift = true)
        assertTrue(o.summary().any { it.contains("not scored") })
    }

    @Test fun aSpeechResultCountsAsDoneOnlyWhenItWasUsable() {
        val plan = QuickCheckPlan(speechAvailable = true)
        val o = QuickCheckOutcome()
        assertFalse(o.done(CheckStepId.SPEECH))
        o.speech = com.thakaan.brain.SpeechCode.check("1234", emptyList(), null)   // nothing heard: valid but all wrong
        assertTrue(o.done(CheckStepId.SPEECH))
        assertTrue(o.summary().any { it.contains("did not match") })
        o.speech = com.thakaan.brain.SpeechCode.check("1234", emptyList(), 2.0)     // far too noisy: not usable
        assertFalse(o.done(CheckStepId.SPEECH))
        assertTrue(o.summary().any { it.contains("not usable") })
        assertTrue(plan.steps.contains(CheckStepId.SPEECH))
    }

    @Test fun deliveryPartnersGetThePickupCheckFaceThenPulseThenHowTheyFeel() {
        val p = QuickCheckPlan(speechAvailable = true, workType = WorkType.DELIVERY)
        assertEquals(listOf(CheckStepId.FACE, CheckStepId.FINGERTIP, CheckStepId.SYMPTOMS), p.steps)
        assertTrue("about a minute and a half: ${p.totalSeconds}", p.totalSeconds in 80..100)
    }

    @Test fun constructionWorkersGetPulseFaceHandHoldAndSymptoms() {
        val p = QuickCheckPlan(speechAvailable = true, workType = WorkType.CONSTRUCTION)
        assertEquals(listOf(CheckStepId.FINGERTIP, CheckStepId.FACE, CheckStepId.TREMOR, CheckStepId.SYMPTOMS), p.steps)
        assertFalse("no tapping in gloves, no spoken digits in noise", p.steps.any { it == CheckStepId.TAPPING || it == CheckStepId.SPEECH })
        assertTrue(p.totalSeconds <= 110)
    }

    @Test fun securityWorkersGetNoSpokenNumbers() {
        val p = QuickCheckPlan(speechAvailable = true, workType = WorkType.SECURITY)
        assertEquals(listOf(CheckStepId.FINGERTIP, CheckStepId.FACE, CheckStepId.TAPPING, CheckStepId.SYMPTOMS), p.steps)
    }

    @Test fun otherGigWorkersGetTheGeneralCheckAndItIsTheDefault() {
        assertEquals(QuickCheckPlan(true).steps, QuickCheckPlan(true, WorkType.OTHER_GIG).steps)
        assertTrue(CheckStepId.FACE in QuickCheckPlan(true, WorkType.OTHER_GIG).steps)
    }

    @Test fun everyKindOfWorkHasThePulseAndTheFaceCheckAndEndsWithHowDoYouFeel() {
        WorkType.entries.forEach { w ->
            val steps = QuickCheckPlan(true, w).steps
            assertTrue(w.name, steps.first() == CheckStepId.FINGERTIP || steps.first() == CheckStepId.FACE)
            assertTrue(w.name, CheckStepId.FACE in steps && CheckStepId.FINGERTIP in steps)
            assertEquals(w.name, CheckStepId.SYMPTOMS, steps.last())
        }
    }

    @Test fun theFaceStepCountsAsDoneOnlyWhenTheCameraReadSomething() {
        val o = QuickCheckOutcome()
        assertFalse(o.done(CheckStepId.FACE))
        o.faceSummary = "Face check not usable: too dark"
        assertFalse(o.done(CheckStepId.FACE))
        assertTrue(o.summary().any { it.contains("not usable") })
        o.faceDone = true; o.faceSummary = "Face check done"
        assertTrue(o.done(CheckStepId.FACE))
    }

    @Test fun aSkippedFaceStepIsRecordedAndStillFinishesTheCheck() {
        val plan = QuickCheckPlan(false, WorkType.DELIVERY)
        val o = QuickCheckOutcome()
        o.heart = heart(true); o.fitts = fitts(true); o.symptoms = emptySet()
        assertFalse("the face step is neither done nor skipped yet", o.isComplete(plan))
        o.skipped[CheckStepId.FACE] = "worker skipped"
        assertTrue(o.isComplete(plan))
        assertTrue(o.summary().any { it.contains("Look at the screen") && it.contains("skipped") })
    }

    @Test fun onlyDeliveryPartnersRide() {
        assertTrue(WorkType.DELIVERY.rides)
        assertFalse(WorkType.CONSTRUCTION.rides || WorkType.SECURITY.rides || WorkType.OTHER_GIG.rides)
    }
}
