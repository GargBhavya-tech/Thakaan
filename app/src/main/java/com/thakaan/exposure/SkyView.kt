package com.thakaan.exposure

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** One satellite as reported by GnssStatus. */
class SatObs(val svid: Int, val constellation: Int, val cn0DbHz: Double, val elevationDeg: Double, val azimuthDeg: Double)

enum class ShadeType { OPEN, TREE_CANOPY, STRUCTURE, UNKNOWN }

class SkyViewState(
    /** Cosine-weighted share of the visible sky that is open, 0 to 1. Null until enough satellites have been seen. */
    val skyViewFactor: Double?,
    /** True/false when the sky around the sun's direction has been observed; null when unknown (treated as not shaded by callers). */
    val sunBlocked: Boolean?,
    val shadeType: ShadeType,
    val satellitesUsed: Int,
)

/**
 * E12 to E14 with rules first (C/N0 and elevation); the XGBoost classifier and L1/L5 features are a later upgrade.
 * Sky is split into 10 degree cells. Each cell keeps log-odds of line of sight, updated every second and decayed so the
 * window is about 5 to 10 s. The rider's own body blocks satellites behind them; a mask learned by relative azimuth
 * stops that being read as shade.
 */
class SkyViewEstimator(private val decay: Double = 0.85) {
    private val azBins = 36
    private val elBins = 9
    private val logOdds = Array(azBins) { DoubleArray(elBins) }
    private val seen = Array(azBins) { BooleanArray(elBins) }
    private val bodyMask = DoubleArray(36) // learned extra C/N0 deficit (dB) by relative azimuth, 10 degree bins
    private val bodyCount = IntArray(36)
    private val cn0History = HashMap<Int, ArrayDeque<Double>>()

    private fun expectedCn0(elDeg: Double) = 30.0 + 15.0 * sin(Math.toRadians(elDeg.coerceIn(0.0, 90.0)))

    private fun pLos(deficitDb: Double): Double = 1.0 / (1.0 + exp((deficitDb - 8.0) / 3.0))

    private fun cell(az: Double, el: Double): Pair<Int, Int> =
        (((az % 360 + 360) % 360) / 10).toInt().coerceIn(0, azBins - 1) to (el / 10).toInt().coerceIn(0, elBins - 1)

    fun update(sats: List<SatObs>, headingDeg: Double?, sun: Sun?): SkyViewState {
        for (a in 0 until azBins) for (e in 0 until elBins) logOdds[a][e] *= decay
        val usable = sats.filter { it.elevationDeg > 5.0 && it.cn0DbHz > 0.0 }
        if (usable.size < 4) return SkyViewState(null, null, ShadeType.UNKNOWN, usable.size)

        // learn the body-shadow mask from satellites that look clearly lost behind the rider while the rest of the sky is open
        val rawDeficits = usable.map { expectedCn0(it.elevationDeg) - it.cn0DbHz }
        val openShare = rawDeficits.count { it < 6.0 }.toDouble() / usable.size
        if (headingDeg != null && openShare >= 0.5) {
            for ((idx, s) in usable.withIndex()) {
                val rel = (((s.azimuthDeg - headingDeg) % 360 + 360) % 360 / 10).toInt().coerceIn(0, 35)
                val d = rawDeficits[idx].coerceAtLeast(0.0)
                // moving average of the deficit seen behind the rider in otherwise open sky
                bodyCount[rel]++
                bodyMask[rel] += (d - bodyMask[rel]) / minOf(bodyCount[rel], 20)
            }
        }

        for ((idx, s) in usable.withIndex()) {
            var deficit = rawDeficits[idx]
            if (headingDeg != null) {
                val rel = (((s.azimuthDeg - headingDeg) % 360 + 360) % 360 / 10).toInt().coerceIn(0, 35)
                if (bodyCount[rel] >= 5) deficit -= bodyMask[rel] // compensate the learned body shadow
            }
            val p = pLos(deficit).coerceIn(0.02, 0.98)
            val (a, e) = cell(s.azimuthDeg, s.elevationDeg)
            logOdds[a][e] += ln(p / (1 - p))
            seen[a][e] = true
        }

        // cosine-weighted sky-view factor over the cells that have been observed
        var num = 0.0
        var den = 0.0
        for (a in 0 until azBins) for (e in 0 until elBins) {
            if (!seen[a][e]) continue
            val elMid = e * 10.0 + 5.0
            val w = sin(Math.toRadians(elMid)) * cos(Math.toRadians(elMid))
            val p = 1.0 / (1.0 + exp(-logOdds[a][e]))
            num += w * p; den += w
        }
        val svf = if (den > 1e-9) (num / den).coerceIn(0.0, 1.0) else null

        val blocked = sun?.let { sunBlocked(it) }
        return SkyViewState(svf, blocked, shadeType(usable), usable.size)
    }

    /** Is the sky around the sun's direction observed to be blocked? Null when no satellite has been seen near it. */
    private fun sunBlocked(sun: Sun): Boolean? {
        if (sun.elevationDeg < 5.0) return null
        val (a0, e0) = cell(sun.azimuthDeg, sun.elevationDeg)
        var sumP = 0.0
        var n = 0
        for (da in -2..2) for (de in -1..1) {
            val a = ((a0 + da) % azBins + azBins) % azBins
            val e = e0 + de
            if (e !in 0 until elBins || !seen[a][e]) continue
            sumP += 1.0 / (1.0 + exp(-logOdds[a][e])); n++
        }
        if (n < 2) return null
        return sumP / n < 0.3
    }

    /** E13: canopy (moderate drop, high scintillation) vs building/flyover (sharp drop, steady) vs open sky. */
    private fun shadeType(sats: List<SatObs>): ShadeType {
        var drops = 0.0; var scint = 0.0; var n = 0
        for (s in sats) {
            val h = cn0History.getOrPut(s.svid) { ArrayDeque() }
            h.addLast(s.cn0DbHz); if (h.size > 6) h.removeFirst()
            if (h.size < 4) continue
            val m = h.average()
            val sd = sqrt(h.sumOf { (it - m) * (it - m) } / h.size)
            drops += (expectedCn0(s.elevationDeg) - m).coerceAtLeast(0.0); scint += sd; n++
        }
        if (n < 4) return ShadeType.UNKNOWN
        val drop = drops / n; val sd = scint / n
        return when {
            drop < 3.0 -> ShadeType.OPEN
            drop in 3.0..15.0 && sd > 2.0 -> ShadeType.TREE_CANOPY
            drop > 10.0 && sd <= 2.0 -> ShadeType.STRUCTURE
            else -> ShadeType.UNKNOWN
        }
    }
}
