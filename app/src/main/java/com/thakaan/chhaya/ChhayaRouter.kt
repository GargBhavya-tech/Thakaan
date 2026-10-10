package com.thakaan.chhaya

import com.thakaan.risk.Level
import java.util.PriorityQueue
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class Route(
    val level: Level,
    val arcs: List<Int>,
    val distanceM: Double,
    val timeSec: Double,
    val fastestTimeSec: Double,
    /** Length-weighted share of the route that is in shade (1 - sun), using the confidence-adjusted layer. */
    val shadeShare: Double,
    /** Share of the route length on segments whose shade rests on guessed building heights. */
    val lowConfidenceShare: Double,
    val stop: Poi?,
    val note: String,
    val lambdaUsed: Double,
    /** How many edges of this route carry an active flood report. Non-zero only when no way round was found. */
    val floodedEdges: Int = 0,
) {
    val extraSec: Double get() = max(0.0, timeSec - fastestTimeSec)
    /** The chip on the map: extra time first, shade share second. */
    val chip: String get() = "+${(extraSec / 60.0).roundToInt()} min, ${(shadeShare * 100).roundToInt()}% shade"
}

/**
 * Strain-aware routing (#48). Edge cost = travel time x (1 + lambda(TSS) x sun fraction) + a heat-island penalty on
 * sunny arterials. At Cool lambda is exactly 0, so the route IS the fastest route; a rider is never made to spend time
 * they did not need to spend. Detour budgets: Warming +1 min, Strained +3 min; Danger drops the destination and goes to
 * the nearest cool seated stop.
 */
class ChhayaRouter(private val g: RoadGraph, private val shade: ShadeLayer, private val floods: FloodReports? = null) {
    companion object {
        const val WARMING_BUDGET_SEC = 60.0
        const val STRAINED_BUDGET_SEC = 180.0
        const val MAX_LAMBDA = 3.0
        const val ISLAND_PENALTY = 0.15
        const val ARTERIAL_KMH = 28.0
        const val EXPOSED_SEAT_PENALTY_SEC = 90.0

        /** The F term: an edge with an active flood report costs this many extra seconds, so it is used only if there is no other way. */
        const val FLOOD_PENALTY_SEC = 100_000.0

        /** 0 below the Cool boundary (TSS 30), then rising to MAX_LAMBDA by TSS 75. */
        fun lambdaFor(tss: Int): Double = if (tss < 30) 0.0 else min(1.0, (tss - 30) / 45.0) * MAX_LAMBDA
    }

    init { require(shade.edgeCount == g.edgeCount) { "shade layer does not match the graph" } }

    private class Search(val dist: DoubleArray, val parentArc: IntArray)

    private fun arcTime(a: Int): Double {
        val e = g.arcEdge[a]
        return g.edgeLenM[e] / (g.edgeSpeedKmh[e] / 3.6)
    }

    private fun search(source: Int, reverse: Boolean, cost: (Int) -> Double): Search {
        val n = g.nodeCount
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val parent = IntArray(n) { -1 }
        class Item(val node: Int, val d: Double)
        val pq = PriorityQueue<Item>(compareBy { it.d })
        dist[source] = 0.0
        pq.add(Item(source, 0.0))
        while (pq.isNotEmpty()) {
            val it = pq.poll()
            if (it.d > dist[it.node]) continue
            val visit = { a: Int ->
                val other = if (reverse) g.arcFrom[a] else g.arcTo[a]
                val nd = it.d + cost(a)
                if (nd < dist[other]) { dist[other] = nd; parent[other] = a; pq.add(Item(other, nd)) }
            }
            if (reverse) g.forIn(it.node, visit) else g.forOut(it.node, visit)
        }
        return Search(dist, parent)
    }

    private fun pathTo(s: Search, source: Int, target: Int, reverse: Boolean): List<Int>? {
        if (s.dist[target] == Double.MAX_VALUE) return null
        val arcs = ArrayList<Int>()
        var cur = target
        while (cur != source) {
            val a = s.parentArc[cur]
            arcs += a
            cur = if (reverse) g.arcTo[a] else g.arcFrom[a]
        }
        // forward search yields target->source order, reverse search already yields source->target order
        return if (reverse) arcs else arcs.reversed()
    }

    private fun timeOf(arcs: List<Int>) = arcs.sumOf { arcTime(it) }

    fun route(
        originLat: Double, originLon: Double, destLat: Double, destLon: Double,
        level: Level, tss: Int, minuteOfDay: Int, nowMs: Long = System.currentTimeMillis(),
    ): Route? {
        val closed = floods?.closedEdges(nowMs) ?: emptySet()
        fun flood(a: Int) = if (closed.isNotEmpty() && g.arcEdge[a] in closed) FLOOD_PENALTY_SEC else 0.0
        val o = g.nearestNode(originLat, originLon)
        val d = g.nearestNode(destLat, destLon)
        val sun = DoubleArray(g.edgeCount) { shade.sunFraction(it, minuteOfDay) }
        fun weighted(lam: Double): (Int) -> Double = { a ->
            val e = g.arcEdge[a]
            val t = arcTime(a)
            val island = if (g.edgeSpeedKmh[e] >= ARTERIAL_KMH && lam > 0.0) ISLAND_PENALTY * t * sun[e] else 0.0
            t * (1.0 + lam * sun[e]) + island + flood(a)
        }

        val fastestSearch = search(o, false) { a -> arcTime(a) + flood(a) }
        val fastestArcs = pathTo(fastestSearch, o, d, false) ?: return null
        val fastestTime = timeOf(fastestArcs)

        fun build(arcs: List<Int>, stop: Poi?, note: String, lam: Double): Route {
            var len = 0.0; var shadeLen = 0.0; var lowLen = 0.0
            for (a in arcs) {
                val e = g.arcEdge[a]
                len += g.edgeLenM[e]
                shadeLen += g.edgeLenM[e] * (1.0 - sun[e])
                if (shade.isLowConfidence(e)) lowLen += g.edgeLenM[e]
            }
            val wet = if (closed.isEmpty()) 0 else arcs.count { g.arcEdge[it] in closed }
            return Route(level, arcs, len, timeOf(arcs), fastestTime, if (len > 0) shadeLen / len else 1.0, if (len > 0) lowLen / len else 0.0, stop,
                if (wet > 0) "$note. Crosses a road reported flooded: go slowly or turn back" else note, lam, wet)
        }

        if (level == Level.COOL) {
            return build(fastestArcs, null, "Fastest route", 0.0)
        }

        if (level == Level.DANGER) return dangerRoute(o, sun, ::build, weighted(MAX_LAMBDA), fastestTime)

        val budget = if (level == Level.WARMING) WARMING_BUDGET_SEC else STRAINED_BUDGET_SEC
        val lamFull = max(lambdaFor(tss), if (level == Level.STRAINED) 1.0 else 0.5)
        // largest share of lambda whose route still fits the detour budget
        var lam = lamFull
        var arcs = pathTo(search(o, false, weighted(lam)), o, d, false) ?: return null
        if (timeOf(arcs) > fastestTime + budget) {
            var lo = 0.0; var hi = lamFull
            var best = fastestArcs; var bestLam = 0.0
            repeat(12) {
                val mid = (lo + hi) / 2
                val cand = pathTo(search(o, false, weighted(mid)), o, d, false)
                if (cand != null && timeOf(cand) <= fastestTime + budget) { lo = mid; best = cand; bestLam = mid } else hi = mid
            }
            arcs = best; lam = bestLam
        }
        if (level == Level.WARMING) return build(arcs, null, "Shadier route within +1 min", lam)

        // Strained: insert the best listed water stop whose total detour stays inside the budget
        val fromO = search(o, false, weighted(lam))
        val toD = search(d, true, weighted(lam))
        val candidates = g.pois.filter { it.type == PoiType.WATER && fromO.dist[it.node] < Double.MAX_VALUE && toD.dist[it.node] < Double.MAX_VALUE }
            .sortedBy { fromO.dist[it.node] + toD.dist[it.node] }.take(8)
        for (p in candidates) {
            val a1 = pathTo(fromO, o, p.node, false) ?: continue
            val a2 = pathTo(toD, d, p.node, true) ?: continue
            val both = a1 + a2
            if (timeOf(both) <= fastestTime + budget) {
                return build(both, p, "Water stop: ${p.label}", lam)
            }
        }
        return build(arcs, null, "No listed water stop within +3 min; shadier route kept", lam)
    }

    private fun dangerRoute(
        o: Int, sun: DoubleArray, build: (List<Int>, Poi?, String, Double) -> Route, cost: (Int) -> Double, fastestTime: Double,
    ): Route? {
        val s = search(o, false, cost)
        val seated = setOf(PoiType.SHELTER, PoiType.TEA, PoiType.HOSPITAL, PoiType.SHOP, PoiType.SEAT)
        val best = g.pois.filter { it.type in seated && s.dist[it.node] < Double.MAX_VALUE }
            .minByOrNull { s.dist[it.node] + (if (it.type == PoiType.SEAT) EXPOSED_SEAT_PENALTY_SEC else 0.0) }
        if (best != null) {
            val arcs = pathTo(s, o, best.node, false) ?: emptyList()
            return build(arcs, best, "Danger: stop now at ${best.label}", MAX_LAMBDA)
        }
        // no listed stop: nearest node whose outgoing roads are mostly shaded
        var shaded = -1; var sd = Double.MAX_VALUE
        for (n in 0 until g.nodeCount) {
            if (s.dist[n] >= sd) continue
            var cnt = 0; var tot = 0.0
            g.forOut(n) { a -> cnt++; tot += sun[g.arcEdge[a]] }
            if (cnt > 0 && tot / cnt <= 0.3) { shaded = n; sd = s.dist[n] }
        }
        if (shaded < 0) return null
        return build(pathTo(s, o, shaded, false) ?: emptyList(), null, "Danger: nearest shade", MAX_LAMBDA)
    }
}
