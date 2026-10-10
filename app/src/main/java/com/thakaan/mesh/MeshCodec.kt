package com.thakaan.mesh

import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A flood somebody reported: where, when and for how long. Anonymous: no device, name or rider id travels with it. */
class FloodNotice(val id: Int, val latMicro: Int, val lonMicro: Int, val reportedAtSec: Long, val ttlMin: Int) {
    val lat: Double get() = latMicro / 1e6
    val lon: Double get() = lonMicro / 1e6
}

/** A buddy's level, readable only by phones that share the pairing code. Level only: no heart rate, no scores. */
class BuddyAlert(val id: Int, val memberTag: Int, val levelOrdinal: Int, val atSec: Long)

sealed class MeshMessage(val hops: Int) {
    class Flood(val notice: FloodNotice, hops: Int) : MeshMessage(hops)
    class Buddy(val alert: BuddyAlert, hops: Int) : MeshMessage(hops)
}

/**
 * The crew mesh wire format: small binary messages that fit one Bluetooth LE extended advertisement (no connection, no
 * pairing at the radio level, so it works with nothing but the phones). Layout, big endian:
 *   byte 0   version (high nibble, 1) and type (low nibble: 1 flood, 2 buddy alert)
 *   byte 1   hops left (0 to [MAX_HOPS]); a relay lowers it by one and stops at 0
 *   bytes 2-5 message id (random), used to drop a message that comes back round
 *   flood:   lat micro-degrees int32, lon micro-degrees int32, reported-at epoch seconds uint32, ttl minutes uint16  (20 bytes)
 *   buddy:   member tag 4 bytes, level 1 byte, time uint32, MAC 8 bytes  (23 bytes)
 * The buddy MAC is HMAC-SHA256 truncated to 8 bytes over type, id, tag, level and time with the pairing key, and it leaves the
 * hop count out so a relay can lower it without breaking it. A phone without the key cannot read whose message it is or forge
 * one. A flood message carries no identity, so it needs no key; receivers limit what they accept (see [MeshNode]).
 */
object MeshCodec {
    const val VERSION = 1
    const val TYPE_FLOOD = 1
    const val TYPE_BUDDY = 2
    const val MAX_HOPS = 3
    const val MAX_TTL_MIN = 180
    private const val FLOOD_LEN = 20
    private const val BUDDY_LEN = 23

    fun encodeFlood(n: FloodNotice, hops: Int): ByteArray {
        require(hops in 0..MAX_HOPS && n.ttlMin in 1..MAX_TTL_MIN)
        return ByteBuffer.allocate(FLOOD_LEN).put(((VERSION shl 4) or TYPE_FLOOD).toByte()).put(hops.toByte()).putInt(n.id)
            .putInt(n.latMicro).putInt(n.lonMicro).putInt(n.reportedAtSec.toInt()).putShort(n.ttlMin.toShort()).array()
    }

    fun encodeBuddy(a: BuddyAlert, hops: Int, key: ByteArray): ByteArray {
        require(hops in 0..MAX_HOPS)
        val b = ByteBuffer.allocate(BUDDY_LEN).put(((VERSION shl 4) or TYPE_BUDDY).toByte()).put(hops.toByte()).putInt(a.id)
            .putInt(a.memberTag).put(a.levelOrdinal.toByte()).putInt(a.atSec.toInt())
        b.put(mac(key, a))
        return b.array()
    }

    /** The message, or null for anything malformed, the wrong version, or (buddy) not authentic under [buddyKey]. Never throws. */
    fun decode(bytes: ByteArray, buddyKey: ByteArray? = null): MeshMessage? = try {
        if (bytes.size < 2) null else {
            val b = ByteBuffer.wrap(bytes)
            val head = b.get().toInt() and 0xFF
            val hops = b.get().toInt() and 0xFF
            if ((head shr 4) != VERSION || hops > MAX_HOPS) null else when (head and 0x0F) {
                TYPE_FLOOD -> if (bytes.size != FLOOD_LEN) null else {
                    val id = b.int; val lat = b.int; val lon = b.int; val at = b.int.toLong() and 0xFFFFFFFFL; val ttl = b.short.toInt() and 0xFFFF
                    if (ttl !in 1..MAX_TTL_MIN || lat !in -90_000_000..90_000_000 || lon !in -180_000_000..180_000_000) null
                    else MeshMessage.Flood(FloodNotice(id, lat, lon, at, ttl), hops)
                }
                TYPE_BUDDY -> if (bytes.size != BUDDY_LEN || buddyKey == null) null else {
                    val id = b.int; val tag = b.int; val level = b.get().toInt() and 0xFF; val at = b.int.toLong() and 0xFFFFFFFFL
                    val got = ByteArray(8).also { b.get(it) }
                    val alert = BuddyAlert(id, tag, level, at)
                    if (level > 3 || !MessageDigest.isEqual(got, mac(buddyKey, alert))) null else MeshMessage.Buddy(alert, hops)
                }
                else -> null
            }
        }
    } catch (_: Exception) { null }

    /** The same message with one hop fewer, ready to pass on; null when it has no hops left. */
    fun relay(m: MeshMessage, buddyKey: ByteArray?): ByteArray? {
        if (m.hops <= 0) return null
        return when (m) {
            is MeshMessage.Flood -> encodeFlood(m.notice, m.hops - 1)
            is MeshMessage.Buddy -> if (buddyKey == null) null else encodeBuddy(m.alert, m.hops - 1, buddyKey)
        }
    }

    /** Pairing key from the code both buddies type; the code itself never goes on the air. */
    fun keyFromCode(code: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(("thakaan-buddy:" + code.trim()).toByteArray())

    /** A stable 4-byte tag for a member under this key, so buddies can tell who it is without a name on the air. */
    fun memberTag(key: ByteArray, memberName: String): Int {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        return ByteBuffer.wrap(m.doFinal(("tag:" + memberName).toByteArray())).int
    }

    private fun mac(key: ByteArray, a: BuddyAlert): ByteArray {
        val m = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        val msg = ByteBuffer.allocate(14).put(TYPE_BUDDY.toByte()).putInt(a.id).putInt(a.memberTag).put(a.levelOrdinal.toByte()).putInt(a.atSec.toInt()).array()
        return m.doFinal(msg).copyOf(8)
    }
}
