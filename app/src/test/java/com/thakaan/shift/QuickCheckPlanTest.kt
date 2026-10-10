package com.thakaan.shift

import com.thakaan.brain.FittsResult
import com.thakaan.brain.TremorResult
import com.thakaan.core.Provenance
import com.thakaan.ppg.CheckContext
import com.thakaan.ppg.CheckKind
import com.thakaan.ppg.CheckResult
import com.thakaan.risk.Symptom
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

    @Test fun fitsTheNinetySecondBudget() {
        val p = QuickCheckPlan()
        assertTrue("total ${p.totalSeconds}s", p.totalSeconds <= 90)
        assertFalse(CheckStepId.SPEECH in p.steps)
    }

    @Test fun speechStepOnlyWhenARecogniserExists() {
        val p = QuickCheckPlan(speechAvailable = true)
        assertTrue(CheckStepId.SPEECH in p.steps)
        // the fingertip step grew to 35 s (the pulse must be checked for steadiness); a speech step will need trimming elsewhere to keep 90 s
        assertTrue("with speech ${p.totalSeconds}s", p.totalSeconds <= 100)
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
}
