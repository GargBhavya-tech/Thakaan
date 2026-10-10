package com.thakaan.audit

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExposureChainTest {
    private fun sample(n: Int = 6, signer: ChainSigner = SoftwareSigner()): ExposureChain {
        val c = ExposureChain(signer)
        for (i in 0 until n) c.append(1_760_000_000_000L + i * 3_600_000L, 10.0 * i, 5.0, listOf("COOL", "WARMING", "STRAINED")[i % 3], "REAL")
        return c
    }

    private fun tamper(json: String, edit: (JSONObject) -> Unit): String {
        val o = JSONObject(json); edit(o); return o.toString()
    }

    @Test fun anUntouchedExportVerifiesOk() {
        val c = sample()
        val v = ExposureChain.verify(c.export(), expectedHead = c.headHash(), expectedCount = c.size)
        assertEquals(ChainStatus.OK, v.status)
        assertTrue("banner must not claim truth: ${v.banner}", v.banner.contains("does not prove"))
    }

    @Test fun changingOneByteOfAnyFieldIsDetected() {
        val c = sample()
        val export = c.export()
        for (field in listOf("t", "sun", "rest", "peak", "prov")) for (idx in listOf(0, 3, 5)) {
            val bad = tamper(export) { o ->
                val e = o.getJSONArray("entries").getJSONObject(idx)
                when (field) {
                    "t" -> e.put("t", e.getLong("t") + 1)
                    "sun" -> e.put("sun", e.getDouble("sun") + 0.001)
                    "rest" -> e.put("rest", e.getDouble("rest") + 0.001)
                    "peak" -> e.put("peak", if (e.getString("peak") == "COOL") "WARMING" else "COOL")
                    "prov" -> e.put("prov", "SIMULATED")
                }
            }
            val v = ExposureChain.verify(bad)
            assertEquals("field $field entry $idx", ChainStatus.BROKEN, v.status)
            assertEquals(idx, v.firstBadIndex)
        }
    }

    @Test fun recomputingTheHashAfterAnEditStillFailsBecauseOfTheSignature() {
        val c = sample()
        val bad = tamper(c.export()) { o ->
            val e = o.getJSONArray("entries").getJSONObject(2)
            e.put("peak", "COOL")
            val body = ExposureChain.canonical(2, e.getLong("t"), e.getDouble("sun"), e.getDouble("rest"), "COOL", e.getString("prov"), e.getString("prev"))
            e.put("hash", ExposureChain.sha256Hex(body)) // a forger fixes the hash but cannot sign
        }
        val v = ExposureChain.verify(bad)
        assertEquals(ChainStatus.BROKEN, v.status)
        assertTrue(v.reason.contains("signature") || v.reason.contains("link"))
    }

    @Test fun deletingAMiddleEntryOrReorderingIsDetected() {
        val c = sample()
        val removed = tamper(c.export()) { o ->
            val a = o.getJSONArray("entries"); val kept = org.json.JSONArray()
            for (i in 0 until a.length()) if (i != 2) kept.put(a.get(i))
            o.put("entries", kept)
        }
        assertEquals(ChainStatus.BROKEN, ExposureChain.verify(removed).status)
        val swapped = tamper(c.export()) { o ->
            val a = o.getJSONArray("entries"); val x = a.get(1); a.put(1, a.get(2)); a.put(2, x)
        }
        assertEquals(ChainStatus.BROKEN, ExposureChain.verify(swapped).status)
    }

    @Test fun truncatingTheEndNeedsAnEarlierHeadAndIsCaughtWhenWeHaveOne() {
        val c = sample()
        val head = c.headHash(); val count = c.size
        val cut = tamper(c.export()) { o ->
            val a = o.getJSONArray("entries"); a.remove(a.length() - 1)
        }
        // without an earlier head the shortened chain is internally consistent, and we say so honestly
        assertEquals(ChainStatus.OK, ExposureChain.verify(cut).status)
        assertEquals(ChainStatus.BROKEN, ExposureChain.verify(cut, expectedHead = head).status)
        assertEquals(ChainStatus.BROKEN, ExposureChain.verify(cut, expectedCount = count).status)
    }

    @Test fun aForgedChainSignedByAnotherKeyStillVerifiesAgainstItsOwnKeyButNotAgainstTheOriginal() {
        val real = sample(4)
        val forged = sample(4)
        // swapping in the forger's key does not match the signatures of the real chain
        val swapped = tamper(real.export()) { o -> o.put("publicKey", JSONObject(forged.export()).getString("publicKey")) }
        assertEquals(ChainStatus.BROKEN, ExposureChain.verify(swapped).status)
    }

    @Test fun garbageEmptyAndFirstEntryChecks() {
        assertEquals(ChainStatus.BROKEN, ExposureChain.verify("not json").status)
        assertEquals(ChainStatus.EMPTY, ExposureChain.verify(ExposureChain(SoftwareSigner()).export()).status)
        val c = sample(1)
        assertEquals(ExposureChain.GENESIS, c.entries().first().prevHash)
        assertFalse(c.export().contains("hr") || c.export().contains("bpm"))
    }

    @Test fun exportSaysWhetherTheKeyIsHardwareBacked() {
        val o = JSONObject(sample(1).export())
        assertEquals(false, o.getBoolean("hardwareBackedKey"))
        assertNull(o.optJSONObject("raw"))
    }
}
