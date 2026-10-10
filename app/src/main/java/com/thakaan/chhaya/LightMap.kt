package com.thakaan.chhaya

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** One E1 sun/shade reading with where it was taken. [sun] null = unknown, which is never counted as a measurement. */
class ShadeObservation(val tMillis: Long, val lat: Double, val lon: Double, val sun: Boolean?, val accuracyM: Double)

/**
 * HMM map matcher (#50): each point has a few candidate edges; Viterbi prefers staying on the same or a connected
 * edge, so GPS that jumps to the other side of a narrow street, or onto a parallel road, is pulled back.
 */
class MapMatcher(private val g: RoadGraph, private val sigmaM: Double = 15.0, private val maxDistM: Double = 40.0) {
    private class Cand(val edge: Int, val distM: Double)

    private val latScale = 111_320.0
    private val edgeBox: Array<DoubleArray> = Array(g.edgeCount) { e ->
        val geom = g.edgeGeom[e]
        var minLa = 1e9; var maxLa = -1e9; var minLo = 1e9; var maxLo = -1e9
        for (i in geom.indices step 2) { val la = geom[i] / 1e6; val lo = geom[i + 1] / 1e6; minLa = min(minLa, la); maxLa = max(maxLa, la); minLo = min(minLo, lo); maxLo = max(maxLo, lo) }
        doubleArrayOf(minLa, maxLa, minLo, maxLo)
    }

    private fun distToEdge(e: Int, lat: Double, lon: Double): Double {
        val geom = g.edgeGeom[e]
        val k = cos(Math.toRadians(lat)) * latScale
        var best = Double.MAX_VALUE
        for (i in 0 until geom.size - 2 step 2) {
            val ax = (geom[i + 1] / 1e6 - lon) * k; val ay = (geom[i] / 1e6 - lat) * latScale
            val bx = (geom[i + 3] / 1e6 - lon) * k; val by = (geom[i + 2] / 1e6 - lat) * latScale
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            best = min(best, hypot(ax + tc * dx, ay + tc * dy))
        }
        return best
    }

    private fun candidates(lat: Double, lon: Double): List<Cand> {
        val pad = maxDistM / latScale * 1.5
        val padLo = pad / cos(Math.toRadians(lat))
        val out = ArrayList<Cand>()
        for (e in 0 until g.edgeCount) {
            val b = edgeBox[e]
            if (lat < b[0] - pad || lat > b[1] + pad || lon < b[2] - padLo || lon > b[3] + padLo) continue
            val d = distToEdge(e, lat, lon)
            if (d <= maxDistM) out += Cand(e, d)
        }
        return out.sortedBy { it.distM }.take(6)
    }

    private fun connected(a: Int, b: Int): Boolean =
        g.edgeU[a] == g.edgeU[b] || g.edgeU[a] == g.edgeV[b] || g.edgeV[a] == g.edgeU[b] || g.edgeV[a] == g.edgeV[b]

    /** Edge index per point, or -1 where nothing is within range. */
    fun match(points: List<Pair<Double, Double>>): IntArray {
        val n = points.size
        val result = IntArray(n) { -1 }
        val cands = points.map { candidates(it.first, it.second) }
        val scores = ArrayList<DoubleArray>(n)
        val back = ArrayList<IntArray>(n)
        for (i in 0 until n) {
            val c = cands[i]
            val score = DoubleArray(c.size)
            val bp = IntArray(c.size) { -1 }
            val prevC = if (i > 0) cands[i - 1] else emptyList()
            for (j in c.indices) {
                val em = -(c[j].distM * c[j].distM) / (2 * sigmaM * sigmaM)
                if (prevC.isEmpty()) { score[j] = em; continue }
                var bestS = -1e18; var bestK = 0
                for (k in prevC.indices) {
                    val tr = when {
                        prevC[k].edge == c[j].edge -> 0.0
                        connected(prevC[k].edge, c[j].edge) -> -1.2
                        else -> -6.0
                    }
                    val s = scores[i - 1][k] + tr
                    if (s > bestS) { bestS = s; bestK = k }
                }
                score[j] = bestS + em; bp[j] = bestK
            }
            scores += score; back += bp
        }
        // backtrack each run of points that have candidates, starting from its best final state
        var i = n - 1
        while (i >= 0) {
            if (cands[i].isEmpty()) { i--; continue }
            var j = scores[i].indices.maxByOrNull { scores[i][it] } ?: 0
            var t = i
            while (t >= 0 && cands[t].isNotEmpty()) {
                result[t] = cands[t][j].edge
                val b = back[t][j]
                t--
                if (b < 0) break
                j = b
            }
            i = t
        }
        return result
    }
}
/**
 * Bayesian correction of the computed shade layer by measured readings (#50). Per (edge, 30-minute slot) the computed
 * value is a prior worth [priorStrength] observations (fewer when it was low-confidence); measured PASSES along the edge
 * add weight. A pass, not a point, is the unit: single GPS points in narrow streets are never trusted.
 */
class ShadeBelief(private val layer: ShadeLayer, private val priorStrength: Double = 4.0, private val lowConfStrength: Double = 2.0) {
    private val sunObs = HashMap<Long, Double>()
    private val allObs = HashMap<Long, Double>()

    private fun key(edge: Int, slot: Int) = edge.toLong() * 100 + slot
    private fun slotOf(minute: Int) = ((minute - layer.slots.first()) / 30).coerceIn(0, layer.slots.size - 1)

    fun addPass(edge: Int, minuteOfDay: Int, sunShare: Double, weight: Double) {
        val k = key(edge, slotOf(minuteOfDay))
        sunObs[k] = (sunObs[k] ?: 0.0) + sunShare * weight
        allObs[k] = (allObs[k] ?: 0.0) + weight
    }

    fun observations(edge: Int, minuteOfDay: Int): Double = allObs[key(edge, slotOf(minuteOfDay))] ?: 0.0

    fun adjust(edge: Int, minuteOfDay: Int, base: Double): Double {
        val k = key(edge, slotOf(minuteOfDay))
        val n = allObs[k] ?: return base
        val strength = if (layer.isLowConfidence(edge)) lowConfStrength else priorStrength
        return (strength * base + (sunObs[k] ?: 0.0)) / (strength + n)
    }

    /** Wire this belief into the layer so the router and map see measured corrections. */
    fun install() { layer.overlay = ::adjust }
}

/** Turns raw geotagged E1 readings into passes and feeds the belief. */
class LightMapAggregator(private val g: RoadGraph, private val belief: ShadeBelief, private val matcher: MapMatcher = MapMatcher(g)) {
    var maxAccuracyM = 30.0
    var minPassPoints = 3

    /** [minuteOf] converts a timestamp to local minute of day. Returns the number of passes accepted. */
    fun ingest(obs: List<ShadeObservation>, minuteOf: (Long) -> Int): Int {
        val usable = obs.filter { it.sun != null && it.accuracyM <= maxAccuracyM }
        if (usable.isEmpty()) return 0
        val edges = matcher.match(usable.map { it.lat to it.lon })
        var passes = 0
        var i = 0
        while (i < usable.size) {
            if (edges[i] < 0) { i++; continue }
            var j = i
            while (j + 1 < usable.size && edges[j + 1] == edges[i]) j++
            val count = j - i + 1
            if (count >= minPassPoints) {
                var sun = 0
                for (k in i..j) if (usable[k].sun == true) sun++
                val weight = min(1.0, count / 6.0) // longer passes count more, capped at one observation
                belief.addPass(edges[i], minuteOf(usable[i].tMillis), sun.toDouble() / count, weight)
                passes++
            }
            i = j + 1
        }
        return passes
    }
}

