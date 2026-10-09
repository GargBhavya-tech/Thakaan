package com.thakaan.sensorhub

import com.thakaan.core.Provenance

enum class Stream(val label: String) {
    ACCEL("Accelerometer"),
    GYRO("Gyroscope"),
    LIGHT("Light (lux)"),
    CCT("Colour temp sensor"),
    GNSS("Location (GNSS)"),
}

/** One timestamped reading. [tNanos] is on the elapsedRealtimeNanos clock for every stream. */
class Reading(
    val stream: Stream,
    val tNanos: Long,
    val values: FloatArray,
    val provenance: Provenance,
)

/** Per-stream health, for the Home screen and (later) Diagnostics. */
data class StreamStats(
    val count: Long = 0,
    val lastTNanos: Long = 0,
    val lastProvenance: Provenance? = null,
    val ratePerSec: Float = 0f,
    /** True if any reading on this stream was ever not REAL. */
    val sawNonReal: Boolean = false,
)
