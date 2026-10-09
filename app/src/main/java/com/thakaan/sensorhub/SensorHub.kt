package com.thakaan.sensorhub

import com.thakaan.core.Provenance
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * The single place where readings enter the app and get their provenance.
 * Live sensors go through [publishLive] (always REAL). The replay harness (#7) will use [publishReplay].
 * There is deliberately no way to publish a reading with a caller-chosen provenance.
 */
object SensorHub {
    private val _readings = MutableSharedFlow<Reading>(extraBufferCapacity = 2048, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val readings: SharedFlow<Reading> = _readings

    private val _stats = MutableStateFlow<Map<Stream, StreamStats>>(emptyMap())
    val stats: StateFlow<Map<Stream, StreamStats>> = _stats

    private class Acc {
        var count = 0L
        var lastT = 0L
        var lastProv: Provenance? = null
        var sawNonReal = false
        var windowStartNanos = 0L
        var windowCount = 0L
        var rate = 0f
    }

    private val acc = ConcurrentHashMap<Stream, Acc>()

    fun publishLive(stream: Stream, tNanos: Long, values: FloatArray) =
        publish(Reading(stream, tNanos, values, Provenance.REAL))

    fun publishReplay(stream: Stream, tNanos: Long, values: FloatArray) =
        publish(Reading(stream, tNanos, values, Provenance.REPLAY))

    private fun publish(r: Reading) {
        val a = acc.getOrPut(r.stream) { Acc() }
        synchronized(a) {
            a.count++
            a.lastT = r.tNanos
            a.lastProv = r.provenance
            if (r.provenance != Provenance.REAL) a.sawNonReal = true
            if (a.windowStartNanos == 0L) a.windowStartNanos = r.tNanos
            a.windowCount++
            val elapsed = r.tNanos - a.windowStartNanos
            if (elapsed >= 2_000_000_000L) {
                a.rate = a.windowCount * 1e9f / elapsed
                a.windowStartNanos = r.tNanos
                a.windowCount = 0
            }
        }
        _readings.tryEmit(r)
    }

    /** Pushes a snapshot of all stream stats to [stats]; the service calls this about once a second. */
    fun snapshot() {
        _stats.value = acc.mapValues { (_, a) ->
            synchronized(a) { StreamStats(a.count, a.lastT, a.lastProv, a.rate, a.sawNonReal) }
        }
    }

    fun reset() {
        acc.clear()
        _stats.value = emptyMap()
    }
}
