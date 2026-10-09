package com.thakaan.sensorhub

import com.thakaan.core.Provenance
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File

/**
 * Session file format (big-endian):
 *   "THKR" magic, int version (1)
 *   repeated: byte streamOrdinal, long tNanos, byte n, n x float
 * Provenance is deliberately NOT stored: whatever is replayed is stamped REPLAY by the hub (invariant 6).
 */
object SessionFile {
    const val MAGIC = 0x54484B52
    const val VERSION = 1

    fun readAll(file: File): List<Reading> {
        val out = ArrayList<Reading>()
        DataInputStream(BufferedInputStream(file.inputStream())).use { d ->
            require(d.readInt() == MAGIC) { "not a Thakaan session file" }
            require(d.readInt() == VERSION) { "unsupported session version" }
            while (true) {
                val ord = try { d.readByte().toInt() } catch (_: EOFException) { break }
                val t = d.readLong()
                val n = d.readByte().toInt()
                val v = FloatArray(n) { d.readFloat() }
                // The provenance on a file-read Reading is a placeholder: replay re-stamps it via the hub.
                out.add(Reading(Stream.entries[ord], t, v, Provenance.REPLAY))
            }
        }
        return out
    }
}

/** Records every hub reading to [file] until [stop]. Only REAL readings are recorded, so a replay can never be re-recorded as live. */
class StreamRecorder(private val file: File, private val dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private var job: Job? = null
    @Volatile var recorded = 0L
        private set

    fun start(scope: CoroutineScope) {
        recorded = 0
        job = scope.launch(dispatcher) {
            DataOutputStream(BufferedOutputStream(file.outputStream())).use { d ->
                d.writeInt(SessionFile.MAGIC)
                d.writeInt(SessionFile.VERSION)
                SensorHub.readings.collect { r ->
                    if (r.provenance != Provenance.REAL) return@collect
                    d.writeByte(r.stream.ordinal)
                    d.writeLong(r.tNanos)
                    d.writeByte(r.values.size)
                    r.values.forEach { d.writeFloat(it) }
                    recorded++
                }
            }
        }
    }

    suspend fun stop() {
        job?.cancel()
        job?.join()
        job = null
    }
}
