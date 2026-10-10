package com.thakaan.mesh

import android.content.Context
import com.thakaan.risk.Level
import com.thakaan.shift.ShiftRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class BuddyEvent(val tag: String, val level: Level, val atSec: Long)

class CrewMeshState(
    val enabled: Boolean = false,
    val status: String = "Off",
    val paired: Boolean = false,
    val heard: Int = 0,
    val lastHeardSec: Long? = null,
    val buddy: List<BuddyEvent> = emptyList(),
    /** Goes up each time a flood report arrives, so the map knows to look in [CrewMesh.drainFloods]. */
    val floodVersion: Int = 0,
)

/**
 * The crew mesh on this phone: off until the worker turns it on, then flood reports and buddy level alerts hop between nearby
 * phones over Bluetooth LE with no internet. What goes out: where a flood was reported (anonymous), and, only to phones that
 * share the pairing code, that this member reached Strained or Danger (level only, never heart rate or scores; invariant 7).
 * What comes in is limited by [MeshNode]. The pairing code is a shared secret kept in private preferences.
 */
object CrewMesh {
    private val _state = MutableStateFlow(CrewMeshState())
    val state: StateFlow<CrewMeshState> = _state

    private var node: MeshNode? = null
    private var ble: BleMesh? = null
    private var key: ByteArray? = null
    private var memberId: String = ""
    private val floodInbox = ArrayList<FloodNotice>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("thakaan_crew", Context.MODE_PRIVATE)

    /** Loads the saved pairing code and member id (the mesh itself stays off until [enable]). */
    @Synchronized fun init(ctx: Context) {
        val p = prefs(ctx)
        memberId = p.getString("member", null) ?: UUID.randomUUID().toString().also { p.edit().putString("member", it).apply() }
        key = p.getString("code", null)?.let { MeshCodec.keyFromCode(it) }
        if (_state.value.paired != (key != null)) _state.value = copy(paired = key != null)
    }

    @Synchronized fun pair(ctx: Context, code: String): Boolean {
        val c = code.trim()
        if (c.length < 4 || c.length > 12) return false
        prefs(ctx).edit().putString("code", c).apply()
        key = MeshCodec.keyFromCode(c)
        _state.value = copy(paired = true)
        return true
    }

    @Synchronized fun unpair(ctx: Context) {
        prefs(ctx).edit().remove("code").apply()
        key = null
        _state.value = copy(paired = false, buddy = emptyList())
    }

    /** Starts the mesh. Returns null on success, or why it could not start. */
    @Synchronized fun enable(ctx: Context): String? {
        if (memberId.isEmpty()) init(ctx)
        if (ble != null) return null
        val n = MeshNode(
            nowSec = { System.currentTimeMillis() / 1000 },
            position = { ShiftRuntime.engine?.position },
            buddyKey = { key },
            ownTag = { key?.let { MeshCodec.memberTag(it, memberId) } },
        )
        val b = BleMesh(ctx.applicationContext, n) { onReceived(it) }
        val err = b.start()
        if (err != null) { _state.value = copy(enabled = false, status = err); return err }
        node = n; ble = b
        _state.value = copy(enabled = true, status = "On: listening for crew phones nearby")
        return null
    }

    @Synchronized fun disable() {
        ble?.stop(); ble = null; node = null
        _state.value = copy(enabled = false, status = "Off")
    }

    /** A flood report made on this phone goes out to the crew. No-op while the mesh is off. */
    fun publishFlood(lat: Double, lon: Double) {
        node?.originateFlood(lat, lon, System.currentTimeMillis() / 1000)
    }

    /** Called when this phone's level rises. Only Strained and Danger go to buddies, and only when paired. */
    fun onLevelRise(level: Level) {
        if (level < Level.STRAINED) return
        node?.originateBuddy(level.ordinal, System.currentTimeMillis() / 1000)
    }

    /** Flood reports heard since the last call; the map applies them to its road graph. */
    @Synchronized fun drainFloods(): List<FloodNotice> = floodInbox.toList().also { floodInbox.clear() }

    @Synchronized private fun onReceived(r: Received) {
        val now = System.currentTimeMillis() / 1000
        when (r) {
            is Received.Flood -> {
                if (r.applied) floodInbox += r.notice
                _state.value = copy(heard = _state.value.heard + 1, lastHeardSec = now, floodVersion = _state.value.floodVersion + (if (r.applied) 1 else 0))
            }
            is Received.Buddy -> if (!r.mine) {
                val ev = BuddyEvent("%04X".format(r.alert.memberTag and 0xFFFF), Level.entries[r.alert.levelOrdinal.coerceIn(0, 3)], r.alert.atSec)
                _state.value = copy(heard = _state.value.heard + 1, lastHeardSec = now, buddy = (listOf(ev) + _state.value.buddy).take(8))
            }
            is Received.Ignored -> Unit
        }
    }

    private fun copy(
        enabled: Boolean = _state.value.enabled, status: String = _state.value.status, paired: Boolean = _state.value.paired,
        heard: Int = _state.value.heard, lastHeardSec: Long? = _state.value.lastHeardSec, buddy: List<BuddyEvent> = _state.value.buddy,
        floodVersion: Int = _state.value.floodVersion,
    ) = CrewMeshState(enabled, status, paired, heard, lastHeardSec, buddy, floodVersion)
}
