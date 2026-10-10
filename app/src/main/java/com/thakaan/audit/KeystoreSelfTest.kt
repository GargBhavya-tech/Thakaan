package com.thakaan.audit

import android.content.Context
import android.content.pm.PackageManager

/** One line of the on-phone Keystore check: what was tried and whether the outcome was the right one. */
class SelfTestLine(val name: String, val ok: Boolean, val detail: String)

/**
 * Runs the exposure-chain signing path on the real AndroidKeyStore (#54): make a chain, export, verify OK, then change
 * one byte and expect BROKEN, then drop the last entry and expect BROKEN against the head hash seen earlier.
 * A check that cannot fail proves nothing, so each tamper case must turn the verdict red.
 */
object KeystoreSelfTest {
    fun run(ctx: Context): List<SelfTestLine> {
        val out = ArrayList<SelfTestLine>()
        val signer = try { KeystoreSigner("thakaan_selftest_key", ctx) } catch (t: Throwable) {
            return listOf(SelfTestLine("Keystore key", false, "could not create: ${t.message}"))
        }
        val strongBoxFeature = ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        out += SelfTestLine("Keystore key", true, "created; hardware backed ${signer.hardwareBacked}, StrongBox feature $strongBoxFeature")

        val chain = ExposureChain(signer)
        repeat(3) { chain.append(1_000L * (it + 1), 10.0 * it, 5.0, "COOL", "REAL") }
        val exported = chain.export()
        val head = chain.headHash()

        val good = ExposureChain.verify(exported, head, 3)
        out += SelfTestLine("Export verifies", good.status == ChainStatus.OK, good.status.name)

        // change one number inside the entries
        val edited = exported.replaceFirst("\"rest\":5", "\"rest\":6")
        val bad = ExposureChain.verify(edited, head, 3)
        out += SelfTestLine("Changed byte is caught", edited != exported && bad.status == ChainStatus.BROKEN, "${bad.status.name}: ${bad.reason}")

        // remove the last entry: only the expected count / head hash can catch this
        val o = org.json.JSONObject(exported)
        val arr = o.getJSONArray("entries")
        val cut = org.json.JSONArray().also { a -> for (i in 0 until arr.length() - 1) a.put(arr.get(i)) }
        o.put("entries", cut)
        val trunc = ExposureChain.verify(o.toString(), head, 3)
        out += SelfTestLine("Cut-off end is caught", trunc.status == ChainStatus.BROKEN, "${trunc.status.name}: ${trunc.reason}")

        val sig = signer.sign("hello".toByteArray())
        out += SelfTestLine("Wrong data fails signature", !signer.verify("hellp".toByteArray(), sig) && signer.verify("hello".toByteArray(), sig), "right data verifies, changed data does not")
        return out
    }
}
