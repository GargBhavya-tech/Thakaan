package com.thakaan.sensorhub

import kotlinx.coroutines.delay
import java.io.File

/**
 * Feeds a recorded session back through [SensorHub.publishReplay] (so everything is stamped REPLAY)
 * at [speed] times real time, keeping the recorded timestamps so downstream maths sees the original timeline.
 */
class Replayer(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    suspend fun play(file: File, speed: Float) {
        require(speed > 0f)
        val readings = SessionFile.readAll(file)
        if (readings.isEmpty()) return
        val t0 = readings.first().tNanos
        val start = nowMs()
        for (r in readings) {
            val dueMs = ((r.tNanos - t0) / 1_000_000f / speed).toLong()
            val wait = dueMs - (nowMs() - start)
            if (wait > 0) delay(wait)
            SensorHub.publishReplay(r.stream, r.tNanos, r.values)
        }
    }
}
