package com.thakaan.speech

import com.thakaan.brain.SpeechCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigitRecognizerTest {
    private val json = """{"result":[{"conf":0.9,"end":1.0,"start":0.6,"word":"one"},{"conf":0.8,"end":1.5,"start":1.1,"word":"[unk]"},{"conf":0.9,"end":2.0,"start":1.6,"word":"two"}],"text":"one two"}"""

    @Test fun parsesWordsWithTimesAndDropsUnknown() {
        val w = DigitRecognizer.parse(json)
        assertEquals(listOf("one", "two"), w.map { it.text })
        assertEquals(0.6, w[0].startSec, 1e-9)
    }

    @Test fun anEmptyResultGivesNoWords() {
        assertTrue(DigitRecognizer.parse("""{"text":""}""").isEmpty())
    }

    @Test fun recognisedWordsGoThroughTheSpeechCodeCheck() {
        val w = DigitRecognizer.parse(json)
        val r = SpeechCode.check("12", w, 25.0)
        assertTrue(r.correct)
    }

    @Test fun snrIsLoudMinusQuiet() {
        val frames = List(20) { if (it < 4) 20.0 else 60.0 }
        assertEquals(40.0, DigitRecognizer.snr(frames)!!, 1e-9)
        assertNull(DigitRecognizer.snr(listOf(1.0, 2.0)))
        assertNotNull(DigitRecognizer.snr(frames))
    }
}
