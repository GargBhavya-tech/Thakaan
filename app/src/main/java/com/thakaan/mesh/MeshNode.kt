package com.thakaan.mesh

import java.security.SecureRandom
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** What happened to a message that arrived. */
sealed class Received {
    /** Dropped, with the reason: invalid, duplicate, expired, too many and so on. Nothing was passed on. */
    class Ignored(val reason: String) : Received()

    /** A flood report. [applied] is false when it was for somewhere too far away to matter to this phone (it is still passed on). */
    class Flood(val notice: FloodNotice, val applied: Boolean) : Received()

    /** An authentic alert from a buddy; [mine] is true when it is our own message coming back round. */
    class Buddy(val alert: BuddyAlert, val mine: Boolean) : Received()
}

/**
 * The relay brain of the crew mesh, with no radio in it so it can be tested on the laptop. Messages are gossiped: anything new
 * is kept for a while and passed on with one hop fewer, anything seen before is dropped by its id, so a loop of phones settles.
 * Limits that stop one bad or busy phone from taking over the mesh: at most [MAX_HOPS] hops, at most [MAX_FLOODS_PER_HOUR] new
 * flood reports an hour, flood reports only applied within [MAX_DISTANCE_M] of this phone, nothing expired, nothing dated in the
 * future. A flood report from the mesh can only make routes avoid a road (the safe direction); it can never remove a closure.
 */
class MeshNode(
    private val nowSec: () -> Long,
    private val position: () -> Pair<Double, Double>?,
    private val buddyKey: () -> ByteArray?,
    private val ownTag: () -> Int?,
    private val random: java.util.Random = SecureRandom(),
) {
    companion object {
        const val MAX_HOPS = MeshCodec.MAX_HOPS
        const val MAX_DISTANCE_M = 3_000.0
        const val MAX_FLOODS_PER_HOUR = 20
        const val MAX_FUTURE_SEC = 300L
        const val SEEN_KEEP_SEC = 3 * 3600L
        const val OUTBOX_KEEP_SEC = 30 * 60L
        const val OUTBOX_MAX = 32
    }

    private class Outgoing(val bytes: ByteArray, val untilSec: Long)

    private val seen = LinkedHashMap<Int, Long>()
    private val outbox = ArrayDeque<Outgoing>()
    private val floodTimes = ArrayDeque<Long>()

    /** A new flood report made on this phone: queued for the air with the full hop count. Returns the bytes. */
    @Synchronized fun originateFlood(lat: Double, lon: Double, reportedAtSec: Long, ttlMin: Int = MeshCodec.MAX_TTL_MIN): ByteArray {
        val n = FloodNotice(random.nextInt(), Math.round(lat * 1e6).toInt(), Math.round(lon * 1e6).toInt(), reportedAtSec, ttlMin.coerceIn(1, MeshCodec.MAX_TTL_MIN))
        val bytes = MeshCodec.encodeFlood(n, MAX_HOPS)
        seen[n.id] = nowSec(); queue(bytes)
        return bytes
    }

    /** This phone's own level change for its buddies. Null when not paired (no key), so nothing unreadable is ever sent. */
    @Synchronized fun originateBuddy(levelOrdinal: Int, atSec: Long): ByteArray? {
        val key = buddyKey() ?: return null
        val tag = ownTag() ?: return null
        val a = BuddyAlert(random.nextInt(), tag, levelOrdinal.coerceIn(0, 3), atSec)
        val bytes = MeshCodec.encodeBuddy(a, MAX_HOPS, key)
        seen[a.id] = nowSec(); queue(bytes)
        return bytes
    }

    @Synchronized fun receive(raw: ByteArray): Received {
        val now = nowSec()
        trim(now)
        val msg = MeshCodec.decode(raw, buddyKey()) ?: return Received.Ignored("not a valid crew message")
        val id = when (msg) { is MeshMessage.Flood -> msg.notice.id; is MeshMessage.Buddy -> msg.alert.id }
        if (seen.containsKey(id)) return Received.Ignored("already seen")
        when (msg) {
            is MeshMessage.Flood -> {
                val n = msg.notice
                if (now - n.reportedAtSec > n.ttlMin * 60L) return Received.Ignored("expired")
                if (n.reportedAtSec - now > MAX_FUTURE_SEC) return Received.Ignored("dated in the future")
                while (floodTimes.isNotEmpty() && now - floodTimes.first() > 3600) floodTimes.removeFirst()
                if (floodTimes.size >= MAX_FLOODS_PER_HOUR) return Received.Ignored("too many flood reports this hour")
                floodTimes.addLast(now)
                seen[id] = now
                relay(msg)
                val p = position()
                val near = p == null || metres(p.first, p.second, n.lat, n.lon) <= MAX_DISTANCE_M
                return Received.Flood(n, near)
            }
            is MeshMessage.Buddy -> {
                seen[id] = now
                relay(msg)
                return Received.Buddy(msg.alert, msg.alert.memberTag == ownTag())
            }
        }
    }

    /** What to put on the air now: everything still live, newest first. The radio cycles through these. */
    @Synchronized fun pendingForAir(): List<ByteArray> {
        trim(nowSec())
        return outbox.reversed().map { it.bytes }
    }

    private fun relay(m: MeshMessage) {
        MeshCodec.relay(m, buddyKey())?.let { queue(it) }
    }

    private fun queue(bytes: ByteArray) {
        outbox.addLast(Outgoing(bytes, nowSec() + OUTBOX_KEEP_SEC))
        while (outbox.size > OUTBOX_MAX) outbox.removeFirst()
    }

    private fun trim(now: Long) {
        while (outbox.isNotEmpty() && outbox.first().untilSec <= now) outbox.removeFirst()
        val it = seen.entries.iterator()
        while (it.hasNext()) if (now - it.next().value > SEEN_KEEP_SEC) it.remove()
    }

    private fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1); val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        return 2 * r * asin(sqrt(a))
    }
}
