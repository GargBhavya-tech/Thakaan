package com.thakaan.audit

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** Signs and verifies bytes. On the phone this is an AndroidKeyStore (StrongBox when present) EC P-256 key. */
interface ChainSigner {
    /** True only when the private key lives in secure hardware; shown honestly in the export. */
    val hardwareBacked: Boolean
    fun sign(data: ByteArray): ByteArray
    fun verify(data: ByteArray, sig: ByteArray): Boolean
    fun publicKeyBytes(): ByteArray
}

/** In-memory EC P-256 key for the laptop tests and as a clearly labelled fallback when no keystore key can be made. */
class SoftwareSigner : ChainSigner {
    private val pair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    override val hardwareBacked = false
    override fun sign(data: ByteArray): ByteArray = Signature.getInstance("SHA256withECDSA").run { initSign(pair.private); update(data); sign() }
    override fun verify(data: ByteArray, sig: ByteArray): Boolean = verifyWith(pair.public, data, sig)
    override fun publicKeyBytes(): ByteArray = pair.public.encoded
}

internal fun verifyWith(pub: PublicKey, data: ByteArray, sig: ByteArray): Boolean = try {
    Signature.getInstance("SHA256withECDSA").run { initVerify(pub); update(data); verify(sig) }
} catch (_: Exception) { false }

/**
 * One shift entry. Deliberately holds no raw physiology: exposure and the peak LEVEL only (invariant 7).
 * [provenance] is REAL / REPLAY / SIMULATED as stamped upstream.
 */
class ChainEntry(
    val index: Int,
    val tMillis: Long,
    val sunDoseMin: Double,
    val restMin: Double,
    val peakLevel: String,
    val provenance: String,
    val prevHash: String,
    val hash: String,
    val signature: String,
)

enum class ChainStatus { OK, BROKEN, EMPTY }

class ChainVerdict(val status: ChainStatus, val firstBadIndex: Int?, val reason: String) {
    /** The line the console shows. It never claims the observations were true. */
    val banner: String get() = when (status) {
        ChainStatus.OK -> "Chain OK: entries not edited since recording (this does not prove the readings were true)"
        ChainStatus.BROKEN -> "Chain BROKEN at entry ${firstBadIndex ?: "?"}: $reason"
        ChainStatus.EMPTY -> "No entries"
    }
}

/**
 * Hash-chained exposure log (#54). Each entry stores the SHA-256 of the previous entry and is signed. A valid chain
 * proves entries were not edited, removed from the middle or reordered after recording. It does NOT prove the
 * observations were true, and an attacker who removes entries from the END can only be caught against a head hash
 * that was exported or shown earlier, so exports carry the entry count and head hash.
 */
class ExposureChain(private val signer: ChainSigner) {
    private val entries = ArrayList<ChainEntry>()
    val size: Int get() = entries.size
    fun entries(): List<ChainEntry> = entries.toList()
    fun headHash(): String = entries.lastOrNull()?.hash ?: GENESIS

    fun append(tMillis: Long, sunDoseMin: Double, restMin: Double, peakLevel: String, provenance: String): ChainEntry {
        val idx = entries.size
        val prev = headHash()
        val body = canonical(idx, tMillis, sunDoseMin, restMin, peakLevel, provenance, prev)
        val hash = sha256Hex(body)
        val sig = hex(signer.sign(hash.toByteArray()))
        return ChainEntry(idx, tMillis, sunDoseMin, restMin, peakLevel, provenance, prev, hash, sig).also { entries += it }
    }

    fun export(): String {
        val o = JSONObject()
        o.put("format", "thakaan-exposure-chain-v1")
        o.put("count", entries.size)
        o.put("head", headHash())
        o.put("hardwareBackedKey", signer.hardwareBacked)
        o.put("publicKey", hex(signer.publicKeyBytes()))
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().put("i", e.index).put("t", e.tMillis).put("sun", e.sunDoseMin).put("rest", e.restMin)
                .put("peak", e.peakLevel).put("prov", e.provenance).put("prev", e.prevHash).put("hash", e.hash).put("sig", e.signature))
        }
        o.put("entries", arr)
        return o.toString()
    }

    companion object {
        const val GENESIS = "0000000000000000000000000000000000000000000000000000000000000000"

        internal fun canonical(i: Int, t: Long, sun: Double, rest: Double, peak: String, prov: String, prev: String): String =
            "v1|$i|$t|${"%.3f".format(java.util.Locale.ROOT, sun)}|${"%.3f".format(java.util.Locale.ROOT, rest)}|$peak|$prov|$prev"

        /**
         * Verifies an exported chain using the public key INSIDE the export plus, optionally, a head hash and count the
         * verifier saw earlier ([expectedHead], [expectedCount]), which is what catches truncation from the end.
         */
        fun verify(exported: String, expectedHead: String? = null, expectedCount: Int? = null): ChainVerdict {
            val o = try { JSONObject(exported) } catch (_: Exception) { return ChainVerdict(ChainStatus.BROKEN, null, "not a valid export") }
            val arr = o.optJSONArray("entries") ?: return ChainVerdict(ChainStatus.BROKEN, null, "entries missing")
            if (arr.length() == 0) return ChainVerdict(ChainStatus.EMPTY, null, "")
            val pub = try {
                java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(unhex(o.getString("publicKey"))))
            } catch (_: Exception) { return ChainVerdict(ChainStatus.BROKEN, null, "public key unreadable") }
            var prev = GENESIS
            for (k in 0 until arr.length()) {
                val e = arr.getJSONObject(k)
                if (e.getInt("i") != k) return ChainVerdict(ChainStatus.BROKEN, k, "entry out of order or missing")
                if (e.getString("prev") != prev) return ChainVerdict(ChainStatus.BROKEN, k, "link to the previous entry does not match")
                val body = canonical(k, e.getLong("t"), e.getDouble("sun"), e.getDouble("rest"), e.getString("peak"), e.getString("prov"), prev)
                val hash = sha256Hex(body)
                if (hash != e.getString("hash")) return ChainVerdict(ChainStatus.BROKEN, k, "entry content was changed")
                if (!verifyWith(pub, hash.toByteArray(), unhex(e.getString("sig")))) return ChainVerdict(ChainStatus.BROKEN, k, "signature does not match")
                prev = hash
            }
            if (expectedCount != null && arr.length() != expectedCount) return ChainVerdict(ChainStatus.BROKEN, arr.length(), "expected $expectedCount entries, found ${arr.length()}")
            if (expectedHead != null && prev != expectedHead) return ChainVerdict(ChainStatus.BROKEN, arr.length() - 1, "head hash differs from the one seen earlier")
            return ChainVerdict(ChainStatus.OK, null, "")
        }

        fun sha256Hex(s: String): String = hex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
        fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
        fun unhex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
