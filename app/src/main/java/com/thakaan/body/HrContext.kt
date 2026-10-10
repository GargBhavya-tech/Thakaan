package com.thakaan.body

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

enum class BodyPosture { SEATED, STANDING, WALKING, RIDING }

/** Learns this rider's heart-rate recovery after a stop: HR decays from the pre-stop level towards rest with time constant tau. */
class StopTemplate(var tauSec: Double = 75.0) {
    private var n = 0

    fun observe(secSinceStop: Double, hr: Double, preStopHr: Double, restHr: Double) {
        if (secSinceStop !in 20.0..240.0) return
        val a = hr - restHr; val b = preStopHr - restHr
        if (a < 3.0 || b < 10.0 || a >= b) return
        val est = (-secSinceStop / ln(a / b)).coerceIn(30.0, 300.0)
        n++
        tauSec = if (n == 1) 0.5 * tauSec + 0.5 * est else 0.8 * tauSec + 0.2 * est
    }

    fun decayed(secSinceStop: Double, preStopHr: Double, restHr: Double): Double =
        restHr + max(0.0, preStopHr - restHr) * exp(-secSinceStop / tauSec)
}

class ExcessHr(val excessBpm: Double, val expectedBpm: Double, val weight: Double)

/**
 * P9: excess HR = measured minus the HR expected for workload, posture and time since stop. Compares only like with like,
 * and down-weights a reading taken within 30 s of a posture change or dismount.
 */
class ExpectedHr(val restSeatedHr: Double, val template: StopTemplate = StopTemplate()) {
    fun expected(mets: Double, posture: BodyPosture, secSinceStop: Double?, preStopHr: Double?): Double {
        val postureAdd = when (posture) { BodyPosture.SEATED -> 0.0; BodyPosture.STANDING -> 8.0; BodyPosture.WALKING -> 8.0; BodyPosture.RIDING -> 6.0 }
        val workload = restSeatedHr + postureAdd + 8.0 * max(0.0, mets - 1.0)
        if (secSinceStop == null || preStopHr == null) return workload
        return max(workload, template.decayed(secSinceStop, preStopHr, restSeatedHr + postureAdd))
    }

    fun excess(hr: Double, mets: Double, posture: BodyPosture, secSinceStop: Double?, preStopHr: Double?, secSincePostureChange: Double?): ExcessHr {
        val exp = expected(mets, posture, secSinceStop, preStopHr)
        val recentChange = (secSincePostureChange ?: Double.MAX_VALUE) < 30.0 || (secSinceStop ?: Double.MAX_VALUE) < 30.0
        return ExcessHr(hr - exp, exp, if (recentChange) 0.3 else 1.0)
    }
}

/**
 * P10: idle-state drift. HR in the same posture at the same time-since-stop compared with the morning value.
 * No like-for-like morning reading means no drift value at all, never a comparison against a different state.
 */
class IdleDrift {
    private fun bucket(sec: Double) = when { sec < 30 -> 0; sec < 60 -> 1; sec < 120 -> 2; sec < 300 -> 3; else -> 4 }
    private val morning = HashMap<Pair<BodyPosture, Int>, Double>()

    fun recordMorning(posture: BodyPosture, secSinceStop: Double, hr: Double) { morning[posture to bucket(secSinceStop)] = hr }

    fun drift(posture: BodyPosture, secSinceStop: Double, hr: Double): Double? =
        morning[posture to bucket(secSinceStop)]?.let { hr - it }
}

class SitToStandResult(val riseBpm: Double?, val baselineBpm: Double?, val flags: Set<String>)

/** P13: seated 30 s, then stand; heart-rate rise at 30 to 60 s after standing versus the seated baseline. */
object SitToStand {
    /** [hr] is (seconds, bpm) with time 0 at the moment of standing; negative times are seated. */
    fun analyse(hr: List<Pair<Double, Double>>, contactQuality: Double): SitToStandResult {
        val flags = HashSet<String>()
        val base = hr.filter { it.first in -10.0..0.0 }.map { it.second }
        val after = hr.filter { it.first in 30.0..60.0 }.map { it.second }
        if (contactQuality < 0.6) flags += "low_contact_quality"
        if (base.size < 4) flags += "short_baseline"
        if (after.size < 4) flags += "short_response_window"
        if (base.size < 4 || after.size < 4) return SitToStandResult(null, null, flags)
        val b = base.average()
        return SitToStandResult(after.average() - b, b, flags)
    }
}

class PerfusionReading(val pi: Double, val contactQuality: Double, val hot: Boolean)

/**
 * P17: perfusion index high in heat, then a sudden fall across successive checks is a hypovolaemic-strain cue. A fall
 * only counts when contact quality is good, because hard finger pressure also lowers perfusion.
 */
class PerfusionCollapse(private val minQuality: Double = 0.7, private val dropFraction: Double = 0.5) {
    private val good = ArrayList<PerfusionReading>()

    /** Returns true when this reading is a collapse relative to the recent good readings. */
    fun update(r: PerfusionReading): Boolean {
        if (r.contactQuality < minQuality) return false
        val recentHigh = good.takeLast(3).filter { it.hot }.maxOfOrNull { it.pi }
        good += r
        if (recentHigh == null || good.size < 3) return false
        return r.hot && r.pi < dropFraction * recentHigh
    }
}

