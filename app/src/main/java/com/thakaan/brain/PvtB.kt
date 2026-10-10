package com.thakaan.brain

import java.util.Random

/** One trial: [rtMs] is hardware-timestamped reaction time; null = no response before the next stimulus. */
class PvtTrial(val isiMs: Long, val rtMs: Double?, val falseStart: Boolean = false)

class PvtResult(
    val lapses: Int,
    val falseStarts: Int,
    /** Mean of 1/RT in 1/s over valid responses, the most sensitive PVT summary. Lower = worse. */
    val meanResponseSpeed: Double,
    val trials: Int,
    val valid: Boolean,
)

/**
 * N11 PVT-B: 3 minutes, ISI 1 to 4 s, lapse = RT over 500 ms, false start = response under 100 ms (or before the stimulus).
 * Triggered only at a break, to confirm a flag. Never run while riding.
 */
object PvtB {
    const val LAPSE_MS = 500.0
    const val FALSE_START_MS = 100.0

    fun schedule(seed: Long, durationSec: Int = 180): List<Long> {
        val rng = Random(seed)
        val out = ArrayList<Long>()
        var t = 0L
        while (t < durationSec * 1000L) { val isi = 1000L + rng.nextInt(3001); out += isi; t += isi + 500 }
        return out
    }

    fun score(trials: List<PvtTrial>): PvtResult {
        if (trials.size < 20) return PvtResult(0, 0, 0.0, trials.size, false)
        var lapses = 0; var fs = 0
        val speeds = ArrayList<Double>()
        for (t in trials) {
            val rt = t.rtMs
            when {
                t.falseStart || (rt != null && rt < FALSE_START_MS) -> fs++
                rt == null || rt > LAPSE_MS -> { lapses++; if (rt != null) speeds += 1000.0 / rt }
                else -> speeds += 1000.0 / rt
            }
        }
        // a missing response counts as a lapse that contributes a very slow speed, so a person who stops responding scores worse, not "no data"
        val padded = speeds + List(trials.count { it.rtMs == null && !it.falseStart }) { 0.0 }
        return PvtResult(lapses, fs, if (padded.isEmpty()) 0.0 else padded.average(), trials.size, true)
    }

    fun toResult(r: PvtResult, tNanos: Long, hour: Double, device: String): BrainResult? =
        if (!r.valid) null else BrainResult(BrainTest.PVT_SPEED, r.meanResponseSpeed, tNanos, hour, device)
}
