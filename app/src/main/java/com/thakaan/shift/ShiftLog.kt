package com.thakaan.shift

import com.thakaan.core.Provenance
import com.thakaan.risk.Level

class LevelChange(val atSec: Double, val from: Level, val to: Level)

/**
 * What a shift looked like, for the worker's own report screen. Fed one reading of (time, level, score, provenance) at a time.
 * Time is credited to the level that was showing when the interval began. An interval longer than [maxGapSec] means the phone
 * was not reading (killed, off, no data) and is counted as a gap, never as Cool time: losing information must not make the
 * shift look safer. Provenance seconds are kept so a report built from a REPLAY or SIMULATED stretch says so.
 */
class ShiftLog(private val maxGapSec: Double = 120.0) {
    private val secondsAt = DoubleArray(Level.entries.size)
    private val secondsBy = DoubleArray(Provenance.entries.size)
    private val changes = ArrayList<LevelChange>()
    private var firstNanos = -1L
    private var lastNanos = -1L
    private var lastLevel: Level? = null
    private var lastProv: Provenance = Provenance.REAL

    var gapSec = 0.0; private set
    var peakTss = 0; private set
    var peakLevel = Level.COOL; private set

    fun add(tNanos: Long, level: Level, tss: Int, provenance: Provenance) {
        if (firstNanos < 0) firstNanos = tNanos
        val prev = lastLevel
        if (prev != null && tNanos > lastNanos) {
            val dt = (tNanos - lastNanos) / 1e9
            if (dt <= maxGapSec) { secondsAt[prev.ordinal] += dt; secondsBy[lastProv.ordinal] += dt } else gapSec += dt
        }
        if (prev != null && prev != level) {
            changes += LevelChange((tNanos - firstNanos) / 1e9, prev, level)
            if (changes.size > 200) changes.removeAt(0)
        }
        if (tss > peakTss) peakTss = tss
        if (level > peakLevel) peakLevel = level
        lastLevel = level; lastNanos = tNanos; lastProv = provenance
    }

    val isEmpty: Boolean get() = lastLevel == null
    val coveredSec: Double get() = secondsAt.sum()
    fun secondsAt(level: Level): Double = secondsAt[level.ordinal]
    fun secondsFrom(p: Provenance): Double = secondsBy[p.ordinal]
    fun changes(): List<LevelChange> = changes.toList()

    /** Share of the covered time that came from this provenance, 0..1. */
    fun shareFrom(p: Provenance): Double = if (coveredSec <= 0) 0.0 else secondsBy[p.ordinal] / coveredSec

    /** True only when every covered second was a real reading: a report with replayed or simulated time must not look real. */
    val allReal: Boolean get() = coveredSec > 0 && secondsBy[Provenance.REPLAY.ordinal] + secondsBy[Provenance.SIMULATED.ordinal] == 0.0

    /**
     * Levels only, safe to hand to a supervisor (invariant 7): minutes at each level, the highest level reached and how much
     * of it was real. No heart rate, no brain scores, no symptoms, no strain number.
     */
    fun levelsOnlySummary(): String {
        if (isEmpty) return "No shift data yet."
        val sb = StringBuilder("Thakaan shift summary (levels only)\n")
        Level.entries.forEach { sb.append("${it.word}: ${(secondsAt(it) / 60.0).toInt()} min\n") }
        sb.append("Highest level: ${peakLevel.word}\n")
        if (gapSec > 0) sb.append("No readings for ${(gapSec / 60.0).toInt()} min\n")
        sb.append("Data: ${(shareFrom(Provenance.REAL) * 100).toInt()}% real, ${(shareFrom(Provenance.REPLAY) * 100).toInt()}% replay, ${(shareFrom(Provenance.SIMULATED) * 100).toInt()}% simulated\n")
        sb.append("Heat-strain wellbeing tool, not a medical device.")
        return sb.toString()
    }
}
