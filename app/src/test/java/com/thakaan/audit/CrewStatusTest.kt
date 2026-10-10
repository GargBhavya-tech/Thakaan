package com.thakaan.audit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CrewStatusTest {
    private fun status(nonce: String? = "n1", lat: Double? = 12.97163, lon: Double? = 77.59461) =
        CrewStatus("Ravi", "STRAINED", "rising", 75, 48, "REAL", ConsoleSafe.labels(listOf("wbgt_action", "rest_hr_high", "symptom")), 1_760_000_000_000L, lat, lon, nonce)

    @Test fun sealedMessageRoundTripsAndRejectsTampering() {
        val signer = SoftwareSigner()
        val sealed = CrewStatusCodec.seal(status(), signer)
        val back = CrewStatusCodec.open(sealed, expectedNonce = "n1")
        assertNotNull(back)
        assertEquals("STRAINED", back!!.level)
        assertEquals(12.972, back.lat!!, 1e-9) // coarse position
        val bad = JSONObject(sealed).also { o -> o.put("body", o.getString("body").replace("STRAINED", "COOL")) }.toString()
        assertNull("a changed level must not verify", CrewStatusCodec.open(bad))
    }

    @Test fun replayedMessagesWithAnotherNonceAreRejected() {
        val sealed = CrewStatusCodec.seal(status(nonce = "old"), SoftwareSigner())
        assertNull(CrewStatusCodec.open(sealed, expectedNonce = "fresh"))
    }

    @Test fun theConsoleNeverSeesRawPhysiologyOrWhichSymptom() {
        val body = CrewStatusCodec.body(status())
        for (forbidden in listOf("bpm", "heart", "hrv", "tremor", "core", "dizz", "nausea", "confus", "symptom", "psi", "rest heart")) {
            assertFalse("console body leaks '$forbidden': $body", body.lowercase().contains(forbidden))
        }
        val keys = JSONObject(body).keys().asSequence().toSet()
        assertTrue(keys.none { it in setOf("hr", "hrv", "core", "tremor", "symptoms", "brain") })
    }

    @Test fun reasonsAreCoarseDistinctAndAtMostThree() {
        val labels = ConsoleSafe.labels(listOf("wbgt_action", "sun_dose", "shade_unknown", "core_high", "psi_strained", "brain_families", "symptom_severe", "collapse"))
        assertEquals(3, labels.size)
        assertEquals(labels.distinct(), labels)
        assertEquals("Possible fall", ConsoleSafe.label("collapse"))
        assertEquals("Worker reported feeling unwell", ConsoleSafe.label("symptom_exposure"))
        assertEquals("Body strain", ConsoleSafe.label("rest_hr_high"))
    }

    @Test fun locationCanBeWithheld() {
        val b = JSONObject(CrewStatusCodec.body(status(lat = null, lon = null)))
        assertFalse(b.has("lat")); assertFalse(b.has("lon"))
    }

    /** Fixtures for console/verify_test.js, which proves the browser verifier agrees with this Kotlin signer. */
    @Test fun writeFixturesForTheBrowserVerifier() {
        val dir = File("build/console-fixtures").also { it.mkdirs() }
        val signer = SoftwareSigner()
        File(dir, "status.json").writeText(CrewStatusCodec.seal(status(nonce = "abc"), signer))
        val chain = ExposureChain(signer)
        for (i in 0 until 5) chain.append(1_760_000_000_000L + i * 900_000L, 11.5 * i + 0.0625, 7.0 + i * 0.5, listOf("COOL", "WARMING", "STRAINED")[i % 3], "REAL")
        File(dir, "chain_ok.json").writeText(chain.export())
        val tampered = JSONObject(chain.export()).also { it.getJSONArray("entries").getJSONObject(2).put("sun", 99.0) }
        File(dir, "chain_bad.json").writeText(tampered.toString())
        assertTrue(File(dir, "chain_ok.json").length() > 100)
    }
}
