package com.thakaan.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainerTest {
    private val strained = AlertLadder.plan(Level.STRAINED, Lang.EN)
    private val warming = AlertLadder.plan(Level.WARMING, Lang.EN)

    @Test fun everyLanguageAndLevelHasATemplateThatPassesItsOwnValidator() {
        for (lang in Lang.entries) for (level in listOf(Level.WARMING, Level.STRAINED, Level.DANGER)) {
            val plan = AlertLadder.plan(level, lang)
            for (codes in listOf(listOf("wbgt_high"), listOf("rest_debt"), listOf("brain_slow"), listOf("symptom_x"), listOf("core"))) {
                val t = Explainer.template(plan, codes)
                assertTrue("$lang $level $codes empty", t.isNotEmpty())
                assertNull("$lang $level template rejected: $t", Explainer.validate(t, plan))
            }
        }
    }

    @Test fun coolHasNothingToSay() {
        val plan = AlertLadder.plan(Level.COOL, Lang.EN)
        assertEquals("", Explainer.template(plan, listOf("core")))
        assertFalse(Explainer.explain(plan, emptyList()) { _, _, _, _ -> "hello" }.fromModel)
    }

    @Test fun noModelMeansTemplate() {
        val e = Explainer.explain(strained, listOf("wbgt_high"), null)
        assertFalse(e.fromModel); assertEquals(Explainer.template(strained, listOf("wbgt_high")), e.text)
    }

    @Test fun goodRewordingIsAccepted() {
        val e = Explainer.explain(strained, listOf("core"), { _, _, _, _ -> "Your body needs a break. Stop in the shade and drink water, it takes 3 minutes." })
        assertTrue(e.rejectedBecause, e.fromModel)
    }

    @Test fun reassuranceIsRejectedAndTheTemplateIsKept() {
        val e = Explainer.explain(strained, listOf("core"), { _, _, _, _ -> "You are fine, keep working. It takes 3 minutes." })
        assertFalse(e.fromModel); assertNotNull(e.rejectedBecause)
        assertEquals(Explainer.template(strained, listOf("core")), e.text)
    }

    @Test fun modelCannotInventNumbersTemperaturesOrPulse() {
        fun bad(line: String) = Explainer.validate(line, strained)
        assertNotNull(bad("Drink water. It takes 3 minutes. Your pulse is 130."))
        assertNotNull(bad("Stop in shade, 3 minutes, body at 39 degrees"))
        assertNotNull(bad("Stop in shade for 3 minutes. 38.5° is too hot."))
        assertNotNull(bad("Stop in the shade and drink.")) // no time cost
        assertNotNull(bad("Stop in shade and drink, it takes 10 minutes"))
    }

    @Test fun gentleActionCannotBeUpgradedByWording() {
        assertNotNull(Explainer.validate("Stop now and go to shade, it takes 1 minute.", warming))
        assertNotNull(Explainer.validate("This is an emergency. Drink water, 1 minute.", warming))
        assertNull(Explainer.validate("Please drink some water now, it takes 1 minute.", warming))
    }

    @Test fun throwingOrSilentModelFallsBack() {
        assertFalse(Explainer.explain(strained, listOf("core"), { _, _, _, _ -> throw IllegalStateException("gpu lost") }).fromModel)
        assertFalse(Explainer.explain(strained, listOf("core"), { _, _, _, _ -> null }).fromModel)
    }

    @Test fun modelIsNeverGivenTheChanceToChangeTheLevelOrAction() {
        // The explanation carries text only: the plan that was passed in is the plan that gets delivered.
        val e = Explainer.explain(strained, listOf("core"), { _, _, level, action -> "Level is $level so ${action.name}. 3 minutes." })
        assertEquals(Level.STRAINED, strained.level); assertEquals(AlertAction.SHADE_AND_DRINK, strained.action)
        assertTrue(e.text.isNotEmpty())
    }
}
