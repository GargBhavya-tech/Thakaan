package com.thakaan.brain

import com.thakaan.risk.Symptom

class SymptomEntry(val tNanos: Long, val symptoms: Set<Symptom>, val effort1to10: Int?, val trigger: String)

/**
 * N10: the 2-tap symptom check, asked at every check and every level change. Stored on the phone only (raw
 * symptoms never leave it). The risk engine reads [active].
 *
 * Fail-safe rules: the newest answered check defines the current symptoms; ordinary symptoms expire after
 * [windowNanos] without a new answer; severe ones (confusion, faintness) never expire by silence and clear only when
 * a newer check is answered without them.
 */
class SymptomLog(private val windowNanos: Long = 30L * 60 * 1_000_000_000L) {
    private val entries = ArrayList<SymptomEntry>()

    fun record(tNanos: Long, symptoms: Set<Symptom>, effort: Int?, trigger: String) {
        require(effort == null || effort in 1..10) { "effort is 1 to 10" }
        entries += SymptomEntry(tNanos, symptoms, effort, trigger)
    }

    fun active(nowNanos: Long): Set<Symptom> {
        val last = entries.lastOrNull() ?: return emptySet()
        val severe = last.symptoms.filter { it.severe }.toSet()
        return if (nowNanos - last.tNanos <= windowNanos) last.symptoms else severe
    }

    fun lastEffort(): Int? = entries.lastOrNull { it.effort1to10 != null }?.effort1to10
    fun size(): Int = entries.size
}
