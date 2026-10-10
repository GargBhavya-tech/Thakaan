package com.thakaan.audit

import org.json.JSONObject

/**
 * What the supervisor console may see (invariant 7): the LEVEL and coarse reasons, never raw HR, HRV, core trend, brain
 * scores, symptoms or voice. Reasons are mapped from driver codes to category labels here, on the phone, so a driver text
 * that quotes a number or a symptom can never leak.
 */
object ConsoleSafe {
    fun label(code: String): String = when {
        code.startsWith("wbgt") || code == "shade_unknown" || code == "sun_dose" -> "Heat and sun exposure"
        code == "rest_debt" -> "No real rest for a while"
        code.startsWith("brain") -> "Slower reactions than usual"
        code.startsWith("symptom") -> "Worker reported feeling unwell"
        code == "collapse" -> "Possible fall"
        else -> "Body strain" // core, psi, rest_hr, excess_hr, hydration, body, held_body, no_fresh_hr
    }

    fun labels(codes: List<String>): List<String> = codes.map(::label).distinct().take(3)
}

class CrewStatus(
    val member: String,
    val level: String,
    val trend: String,
    val minutesSinceRest: Int,
    val sunDoseMin: Int,
    val provenance: String,
    val reasons: List<String>,
    val tMillis: Long,
    /** Coarse position (about 100 m) for the level-coloured crew map; null when the worker did not share it. */
    val lat: Double?,
    val lon: Double?,
    /** Echo of the console's request nonce, so an old message cannot be replayed as fresh. */
    val nonce: String?,
)

/** A sealed message: the body text, an ECDSA P-256 signature over its UTF-8 bytes, and the signer's public key. */
object CrewStatusCodec {
    fun body(s: CrewStatus): String {
        val o = JSONObject()
        o.put("v", 1).put("member", s.member).put("level", s.level).put("trend", s.trend)
            .put("restMin", s.minutesSinceRest).put("sunMin", s.sunDoseMin).put("prov", s.provenance)
            .put("reasons", org.json.JSONArray(s.reasons)).put("t", s.tMillis)
        if (s.lat != null && s.lon != null) o.put("lat", Math.round(s.lat * 1000) / 1000.0).put("lon", Math.round(s.lon * 1000) / 1000.0)
        if (s.nonce != null) o.put("nonce", s.nonce)
        return o.toString()
    }

    fun seal(s: CrewStatus, signer: ChainSigner): String {
        val b = body(s)
        return JSONObject().put("body", b).put("sig", ExposureChain.hex(signer.sign(b.toByteArray(Charsets.UTF_8))))
            .put("key", ExposureChain.hex(signer.publicKeyBytes())).put("hardwareKey", signer.hardwareBacked).toString()
    }

    /** Verifies the signature against the key in the message. Returns the status only when it checks out. */
    fun open(sealed: String, expectedNonce: String? = null): CrewStatus? = try {
        val o = JSONObject(sealed)
        val body = o.getString("body")
        val pub = java.security.KeyFactory.getInstance("EC").generatePublic(java.security.spec.X509EncodedKeySpec(ExposureChain.unhex(o.getString("key"))))
        if (!verifyWith(pub, body.toByteArray(Charsets.UTF_8), ExposureChain.unhex(o.getString("sig")))) null
        else {
            val b = JSONObject(body)
            val n = if (b.has("nonce")) b.getString("nonce") else null
            if (expectedNonce != null && n != expectedNonce) null
            else CrewStatus(
                b.getString("member"), b.getString("level"), b.getString("trend"), b.getInt("restMin"), b.getInt("sunMin"), b.getString("prov"),
                (0 until b.getJSONArray("reasons").length()).map { b.getJSONArray("reasons").getString(it) }, b.getLong("t"),
                if (b.has("lat")) b.getDouble("lat") else null, if (b.has("lon")) b.getDouble("lon") else null, n,
            )
        }
    } catch (_: Exception) { null }
}
