package com.thakaan.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class MeshTest {
    private val now = 1_800_000_000L
    private val here = 12.9655 to 77.5900

    private class Net(val clock: LongArray, size: Int, key: ByteArray?, pos: Pair<Double, Double>? = 12.9655 to 77.5900) {
        val received = Array(size) { ArrayList<Received>() }
        val nodes = Array(size) { i ->
            MeshNode({ clock[0] }, { pos }, { key }, { key?.let { MeshCodec.memberTag(it, "m$i") } }, Random(i + 1L))
        }
        /** Everyone broadcasts what they hold; only listed neighbours hear. Repeats until nothing new happens. */
        fun run(neighbours: Map<Int, List<Int>>, rounds: Int = 12) {
            repeat(rounds) {
                for (i in nodes.indices) for (bytes in nodes[i].pendingForAir()) for (j in neighbours[i] ?: emptyList()) {
                    val r = nodes[j].receive(bytes.copyOf())
                    if (r !is Received.Ignored) received[j] += r
                }
            }
        }
    }

    private fun chain(n: Int) = (0 until n).associateWith { i -> listOfNotNull(if (i > 0) i - 1 else null, if (i < n - 1) i + 1 else null) }

    @Test fun floodMessagesRoundTripAndBadBytesAreRejected() {
        val n = FloodNotice(77, 12_965_500, 77_590_000, now, 180)
        val m = MeshCodec.decode(MeshCodec.encodeFlood(n, 3)) as MeshMessage.Flood
        assertEquals(12.9655, m.notice.lat, 1e-9); assertEquals(3, m.hops); assertEquals(180, m.notice.ttlMin)
        assertNull(MeshCodec.decode(byteArrayOf()))
        assertNull(MeshCodec.decode(ByteArray(20) { 0x7F }))
        assertNull(MeshCodec.decode(MeshCodec.encodeFlood(n, 3).copyOf(19)))
        val bad = MeshCodec.encodeFlood(n, 3); bad[0] = 0x21  // wrong version
        assertNull(MeshCodec.decode(bad))
    }

    @Test fun buddyAlertsNeedTheSharedKeyAndSurviveRelayingButNotTampering() {
        val key = MeshCodec.keyFromCode("482913"); val other = MeshCodec.keyFromCode("111111")
        val a = BuddyAlert(5, MeshCodec.memberTag(key, "Ravi"), 2, now)
        val raw = MeshCodec.encodeBuddy(a, 3, key)
        assertNotNull(MeshCodec.decode(raw, key))
        assertNull("a phone with another code cannot read it", MeshCodec.decode(raw, other))
        assertNull("no key, no reading", MeshCodec.decode(raw, null))
        val tampered = raw.copyOf(); tampered[10] = 3  // change the level
        assertNull(MeshCodec.decode(tampered, key))
        val relayed = MeshCodec.relay(MeshCodec.decode(raw, key)!!, key)!!
        val again = MeshCodec.decode(relayed, key) as MeshMessage.Buddy
        assertEquals(2, again.hops); assertEquals(2, again.alert.levelOrdinal)
    }

    @Test fun aMessageTravelsThreeHopsBeyondTheOriginAndNoFurther() {
        val net = Net(longArrayOf(now), 7, null)
        net.nodes[0].originateFlood(12.9655, 77.5900, now)
        net.run(chain(7))
        for (i in 1..4) assertEquals("node $i hears it once", 1, net.received[i].count { it is Received.Flood })
        for (i in 5..6) assertTrue("node $i is out of reach", net.received[i].isEmpty())
    }

    @Test fun aLoopOfPhonesSettlesAndEachHearsAMessageOnce() {
        val net = Net(longArrayOf(now), 4, null)
        net.nodes[0].originateFlood(12.9655, 77.5900, now)
        net.run(mapOf(0 to listOf(1, 3), 1 to listOf(0, 2), 2 to listOf(1, 3), 3 to listOf(2, 0)), rounds = 20)
        for (i in 1..3) assertEquals(1, net.received[i].count { it is Received.Flood })
    }

    @Test fun buddyAlertsReachPairedPhonesAndStayUnreadableToOthers() {
        val key = MeshCodec.keyFromCode("482913")
        val net = Net(longArrayOf(now), 3, key)
        net.nodes[0].originateBuddy(2, now)!!
        net.run(chain(3))
        val got = net.received[2].filterIsInstance<Received.Buddy>()
        assertEquals(1, got.size); assertEquals(2, got[0].alert.levelOrdinal); assertFalse(got[0].mine)
        val stranger = Net(longArrayOf(now), 2, MeshCodec.keyFromCode("999999"))
        val raw = net.nodes[0].pendingForAir().first()
        assertTrue(stranger.nodes[1].receive(raw) is Received.Ignored)
    }

    @Test fun anUnpairedPhoneSendsNoBuddyAlert() {
        val net = Net(longArrayOf(now), 1, null)
        assertNull(net.nodes[0].originateBuddy(3, now))
        assertTrue(net.nodes[0].pendingForAir().isEmpty())
    }

    @Test fun staleFutureFarAndFloodedMeshMessagesAreHandledSafely() {
        val clock = longArrayOf(now)
        val rx = MeshNode({ clock[0] }, { here }, { null }, { null }, Random(1))
        fun flood(id: Int, at: Long, lat: Double = here.first, ttl: Int = 60) = MeshCodec.encodeFlood(FloodNotice(id, Math.round(lat * 1e6).toInt(), 77_590_000, at, ttl), 2)
        assertEquals("expired", (rx.receive(flood(1, now - 2 * 3600)) as Received.Ignored).reason)
        assertEquals("dated in the future", (rx.receive(flood(2, now + 3600)) as Received.Ignored).reason)
        val far = rx.receive(flood(3, now, lat = 13.20)) as Received.Flood   // about 26 km away
        assertFalse("far reports are not applied here", far.applied)
        assertEquals("a far report is still passed on", 1, rx.pendingForAir().size)
        assertTrue((rx.receive(flood(4, now)) as Received.Flood).applied)
        assertEquals("already seen", (rx.receive(flood(4, now)) as Received.Ignored).reason)
        for (i in 10 until 10 + MeshNode.MAX_FLOODS_PER_HOUR) rx.receive(flood(i, now))
        assertEquals("too many flood reports this hour", (rx.receive(flood(999, now)) as Received.Ignored).reason)
        clock[0] = now + 3700                                                 // an hour later the allowance is back
        assertTrue(rx.receive(flood(1000, clock[0])) is Received.Flood)
    }
}
