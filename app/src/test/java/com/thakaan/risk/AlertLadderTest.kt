package com.thakaan.risk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertLadderTest {
    @Test fun coolIsSilent() {
        val p = AlertLadder.plan(Level.COOL, Lang.EN)
        assertNull(p.speech)
        assertEquals(AlertScreen.NONE, p.screen)
        assertEquals(0, p.haptic.size)
    }

    @Test fun everyAlertNamesOneActionAndItsTimeCostInEveryLanguage() {
        for (lang in Lang.entries) for (level in listOf(Level.WARMING, Level.STRAINED, Level.DANGER)) {
            val p = AlertLadder.plan(level, lang)
            assertNotNull("$lang $level", p.speech)
            assertFalse("unfilled placeholder in $lang $level", p.speech!!.contains("{n}"))
            assertTrue("time cost digit in $lang $level: ${p.speech}", p.speech.any { it.isDigit() })
            assertEquals(lang, p.speechLang)
            assertNotNull(p.action)
        }
    }

    @Test fun languagesAreActuallyDifferentScripts() {
        val hi = AlertLadder.plan(Level.DANGER, Lang.HI).speech!!
        val kn = AlertLadder.plan(Level.DANGER, Lang.KN).speech!!
        val en = AlertLadder.plan(Level.DANGER, Lang.EN).speech!!
        assertTrue(hi.any { it in 'ऀ'..'ॿ' })   // Devanagari
        assertTrue(kn.any { it in 'ಀ'..'೿' })   // Kannada
        assertTrue(AlertLadder.plan(Level.DANGER, Lang.TA).speech!!.any { it in '஀'..'௿' })
        assertTrue(AlertLadder.plan(Level.DANGER, Lang.TE).speech!!.any { it in 'ఀ'..'౿' })
        assertNotEquals(hi, kn); assertNotEquals(hi, en)
    }

    @Test fun theRealTimeCostFromRoutingIsSpoken() {
        assertTrue(AlertLadder.plan(Level.STRAINED, Lang.EN, minutesOverride = 4).speech!!.contains("4 minutes"))
    }

    @Test fun hapticsAndScreensEscalate() {
        val w = AlertLadder.plan(Level.WARMING, Lang.EN)
        val s = AlertLadder.plan(Level.STRAINED, Lang.EN)
        val d = AlertLadder.plan(Level.DANGER, Lang.EN)
        assertTrue(w.haptic.sum() < s.haptic.sum() && s.haptic.sum() < d.haptic.sum())
        assertEquals(listOf(AlertScreen.AMBER_RING, AlertScreen.ORANGE_BANNER, AlertScreen.RED_TAKEOVER), listOf(w.screen, s.screen, d.screen))
    }

    @Test fun onlyDangerAlertsContactAndSupervisor() {
        for (l in listOf(Level.WARMING, Level.STRAINED)) {
            val p = AlertLadder.plan(l, Lang.EN)
            assertFalse(p.notifyContact); assertFalse(p.notifySupervisor)
        }
        val d = AlertLadder.plan(Level.DANGER, Lang.EN)
        assertTrue(d.notifyContact && d.notifySupervisor)
    }

    @Test fun smartSnoozeNeverAtDanger() {
        val strained = AlertLadder.plan(Level.STRAINED, Lang.EN)
        assertTrue(AlertLadder.mayStayQuiet(strained, 2))
        assertFalse(AlertLadder.mayStayQuiet(strained, 30))
        assertFalse(AlertLadder.mayStayQuiet(AlertLadder.plan(Level.DANGER, Lang.EN), 0))
    }

    @Test fun collapseCountdownPromptExistsInEveryLanguage() {
        for (lang in Lang.entries) assertTrue(AlertLadder.collapsePrompt(lang).isNotBlank())
    }
}
