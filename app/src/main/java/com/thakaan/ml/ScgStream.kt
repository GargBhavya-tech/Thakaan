package com.thakaan.ml

import com.thakaan.core.Provenance
import com.thakaan.sensorhub.Reading
import com.thakaan.sensorhub.Stream
import kotlin.math.sqrt

/**
 * One chest-pulse reading from the continuous stream. [bpm] is null when the last look found nothing it trusts
 * (not still, no clear beat, rate kept changing); [reason] says why. [provenance] is the worst provenance of any sample
 * the reading was computed from, so replay can never show as REAL. [tNanos] is the clock of the newest sample used.
 */
class ScgReading(val tNanos: Long, val bpm: Double?, val quality: Double, val provenance: Provenance, val reason: String?, val breathsPerMin: Double?)

/**
 * Continuous chest-pocket heart rate (P3) on the hub's ACCEL + GYRO streams. Keeps the last [bufferSec] seconds, and every
 * [hopSec] of stream time, if the phone has lately been still, runs [ScgPipeline.analyse] over the last [windowSec]
 * seconds. Batch and stream therefore give the same answer on the same data. It never runs while the body is moving, so it
 * costs nothing on a ride, and a gap in the sensor stream simply restarts the buffer instead of being bridged.
 */
class ScgStream(
    private val windowSec: Double = 30.0,
    private val hopSec: Double = 5.0,
    private val bufferSec: Double = 40.0,
    private val denoise: ((DoubleArray) -> DoubleArray?)? = null,
    private val cancelMotion: Boolean = false,
) {
    private class S(val t: Long, val x: Double, val y: Double, val z: Double, val g: DoubleArray, val prov: Provenance)

    private val buf = ArrayDeque<S>()
    private var gyro = DoubleArray(3); private var gyroProv = Provenance.REAL
    private var lastRunNanos = Long.MIN_VALUE

    fun reset() { buf.clear(); lastRunNanos = Long.MIN_VALUE }

    fun onReading(r: Reading): ScgReading? {
        when (r.stream) {
            Stream.GYRO -> { gyro = doubleArrayOf(r.values[0].toDouble(), r.values[1].toDouble(), r.values[2].toDouble()); gyroProv = r.provenance; return null }
            Stream.ACCEL -> {}
            else -> return null
        }
        // a hole in the stream: start over rather than join two pieces of different moments
        buf.lastOrNull()?.let { if ((r.tNanos - it.t) / 1e9 > ScgPipeline.MAX_GAP_SEC * 5) buf.clear() }
        buf.addLast(S(r.tNanos, r.values[0].toDouble(), r.values[1].toDouble(), r.values[2].toDouble(), gyro.copyOf(), Provenance.combine(r.provenance, gyroProv)))
        while (buf.size > 1 && (r.tNanos - buf.first().t) / 1e9 > bufferSec) buf.removeFirst()

        val span = (buf.last().t - buf.first().t) / 1e9
        if (span < windowSec) return null
        if (lastRunNanos != Long.MIN_VALUE && (r.tNanos - lastRunNanos) / 1e9 < hopSec) return null
        lastRunNanos = r.tNanos
        if (!lookedStill()) return ScgReading(r.tNanos, null, 0.0, worstProvenance(), "Not still: no chest reading", null)

        val take = buf.filter { (r.tNanos - it.t) / 1e9 <= windowSec }
        val t0 = take.first().t
        val rec = ScgRecording(
            DoubleArray(take.size) { (take[it].t - t0) / 1e9 },
            DoubleArray(take.size) { take[it].x }, DoubleArray(take.size) { take[it].y }, DoubleArray(take.size) { take[it].z },
            DoubleArray(take.size) { i -> take[i].g.let { sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2]) } },
            List(3) { k -> DoubleArray(take.size) { take[it].g[k] } },
        )
        val res = ScgPipeline.analyse(rec, cancelMotion, denoise)
        val q = if (res.accepted) (res.consistency * res.motionQuality.coerceAtLeast(0.0) * res.stillWindows / res.windows.coerceAtLeast(1)).coerceIn(0.0, 1.0) else 0.0
        return ScgReading(r.tNanos, res.classicalBpm.takeIf { res.accepted }, q, worstProvenance(), res.reason, res.breathsPerMin)
    }

    /** Cheap pre-check on the newest 5 s so a ride or a walk never pays for the full analysis. */
    private fun lookedStill(): Boolean {
        val end = buf.last().t
        val recent = buf.filter { (end - it.t) / 1e9 <= ScgPipeline.WINDOW_SEC }
        if (recent.size < 10) return false
        val rms = sqrt(recent.sumOf { s -> s.g.sumOf { it * it } } / recent.size)
        return rms <= 2 * ScgPipeline.MAX_GYRO_RMS
    }

    private fun worstProvenance(): Provenance = buf.fold(Provenance.REAL) { p, s -> Provenance.combine(p, s.prov) }
}
