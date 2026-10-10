package com.thakaan.chhaya

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sqrt

/** Where a rider can stop. Water points are "listed, quality unverified": OSM cannot vouch for water quality. */
enum class PoiType { WATER, TEA, SHOP, FUEL, SHELTER, SEAT, HOSPITAL;
    companion object { fun parse(s: String): PoiType? = entries.firstOrNull { it.name.equals(s, true) } }
}

class Poi(val type: PoiType, val lat: Double, val lon: Double, val name: String, val node: Int, val accessM: Double) {
    val label: String get() = if (type == PoiType.WATER) "Water (listed, quality unverified)" else name.ifBlank { type.name.lowercase() }
}

/**
 * The offline road graph produced by tools/chhaya/build_graph.py (data (c) OpenStreetMap contributors, ODbL).
 * Directed arcs are derived from edges and their oneway flag.
 */
class RoadGraph(json: String) {
    val nodeLat: DoubleArray
    val nodeLon: DoubleArray
    val edgeU: IntArray
    val edgeV: IntArray
    val edgeLenM: DoubleArray
    val edgeSpeedKmh: DoubleArray
    val edgeOneway: IntArray
    val edgeGeom: Array<IntArray>
    val pois: List<Poi>

    // directed arcs: arc a goes from arcFrom[a] to arcTo[a] along edge arcEdge[a]
    val arcFrom: IntArray
    val arcTo: IntArray
    val arcEdge: IntArray
    private val outStart: IntArray
    private val outArcs: IntArray
    private val inStart: IntArray
    private val inArcs: IntArray

    init {
        val o = JSONObject(json)
        val nodes = o.getJSONArray("nodes")
        nodeLat = DoubleArray(nodes.length()) { nodes.getJSONArray(it).getDouble(0) }
        nodeLon = DoubleArray(nodes.length()) { nodes.getJSONArray(it).getDouble(1) }
        val edges = o.getJSONArray("edges")
        val m = edges.length()
        edgeU = IntArray(m); edgeV = IntArray(m); edgeLenM = DoubleArray(m); edgeSpeedKmh = DoubleArray(m); edgeOneway = IntArray(m)
        edgeGeom = Array(m) { IntArray(0) }
        for (i in 0 until m) {
            val e = edges.getJSONArray(i)
            edgeU[i] = e.getInt(0); edgeV[i] = e.getInt(1); edgeLenM[i] = e.getDouble(2); edgeSpeedKmh[i] = e.getDouble(3); edgeOneway[i] = e.getInt(4)
            val g = e.getJSONArray(5)
            edgeGeom[i] = IntArray(g.length()) { g.getInt(it) }
        }
        val from = ArrayList<Int>(); val to = ArrayList<Int>(); val ed = ArrayList<Int>()
        for (i in 0 until m) {
            if (edgeOneway[i] >= 0) { from += edgeU[i]; to += edgeV[i]; ed += i }
            if (edgeOneway[i] <= 0) { from += edgeV[i]; to += edgeU[i]; ed += i }
        }
        arcFrom = from.toIntArray(); arcTo = to.toIntArray(); arcEdge = ed.toIntArray()
        val n = nodeLat.size
        val (os, oa) = csr(n, arcFrom)
        outStart = os; outArcs = oa
        val (isr, ia) = csr(n, arcTo)
        inStart = isr; inArcs = ia
        val p = o.getJSONArray("pois")
        pois = (0 until p.length()).mapNotNull { k ->
            val q: JSONObject = p.getJSONObject(k)
            val t = PoiType.parse(q.getString("t")) ?: return@mapNotNull null
            Poi(t, q.getDouble("lat"), q.getDouble("lon"), q.optString("name", ""), q.getInt("node"), q.getDouble("acc_m"))
        }
    }

    val nodeCount: Int get() = nodeLat.size
    val edgeCount: Int get() = edgeU.size
    val arcCount: Int get() = arcFrom.size

    private fun csr(n: Int, key: IntArray): Pair<IntArray, IntArray> {
        val start = IntArray(n + 1)
        for (k in key) start[k + 1]++
        for (i in 0 until n) start[i + 1] += start[i]
        val fill = start.copyOf()
        val arcs = IntArray(key.size)
        for (a in key.indices) arcs[fill[key[a]]++] = a
        return start to arcs
    }

    inline fun forOut(node: Int, f: (Int) -> Unit) { for (i in outStartOf(node) until outStartOf(node + 1)) f(outArcAt(i)) }
    inline fun forIn(node: Int, f: (Int) -> Unit) { for (i in inStartOf(node) until inStartOf(node + 1)) f(inArcAt(i)) }
    fun outStartOf(n: Int) = outStart[n]
    fun outArcAt(i: Int) = outArcs[i]
    fun inStartOf(n: Int) = inStart[n]
    fun inArcAt(i: Int) = inArcs[i]

    fun nearestNode(lat: Double, lon: Double): Int {
        val k = cos(Math.toRadians(lat))
        var best = 0; var bd = Double.MAX_VALUE
        for (i in 0 until nodeCount) {
            val dy = nodeLat[i] - lat; val dx = (nodeLon[i] - lon) * k
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = i }
        }
        return best
    }

    /** The edge whose drawn line passes closest to the point, if one is within [maxM] metres; null otherwise. */
    fun nearestEdge(lat: Double, lon: Double, maxM: Double = 60.0): Int? {
        val k = cos(Math.toRadians(lat))
        var best = -1; var bd = Double.MAX_VALUE
        for (e in 0 until edgeCount) {
            val g = edgeGeom[e]
            var i = 0
            while (i + 3 < g.size) {
                val ay = g[i] / 1e6; val ax = g[i + 1] / 1e6; val by = g[i + 2] / 1e6; val bx = g[i + 3] / 1e6
                // planar distance from the point to the segment in degrees, longitude scaled by cos(lat)
                val px = (lon - ax) * k; val py = lat - ay
                val sx = (bx - ax) * k; val sy = by - ay
                val len2 = sx * sx + sy * sy
                val t = if (len2 < 1e-18) 0.0 else ((px * sx + py * sy) / len2).coerceIn(0.0, 1.0)
                val dx = px - t * sx; val dy = py - t * sy
                val d = dx * dx + dy * dy
                if (d < bd) { bd = d; best = e }
                i += 2
            }
        }
        if (best < 0) return null
        return if (sqrt(bd) * 111_320.0 <= maxM) best else null
    }

    fun metresBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (bLat - aLat) * 111_320.0
        val dx = (bLon - aLon) * 111_320.0 * cos(Math.toRadians((aLat + bLat) / 2))
        return sqrt(dx * dx + dy * dy)
    }

    /** Polyline of an edge walked in the arc direction, as lat/lon pairs. */
    fun arcPolyline(arc: Int): List<Pair<Double, Double>> {
        val g = edgeGeom[arcEdge[arc]]
        val pts = (0 until g.size step 2).map { g[it] / 1e6 to g[it + 1] / 1e6 }
        val forward = arcFrom[arc] == edgeU[arcEdge[arc]]
        return if (forward) pts else pts.reversed()
    }
}

/** Hourly (30-minute) shade fractions per edge from tools/chhaya/shade.py, with a low-confidence flag per edge. */
class ShadeLayer(json: String) {
    val slots: IntArray
    private val shade: Array<IntArray>
    private val lowconf: IntArray

    /** Measured corrections (see ShadeBelief): edge, minute of day and the layer value in, corrected sun fraction out. */
    var overlay: ((Int, Int, Double) -> Double)? = null

    init {
        val o = JSONObject(json)
        val s = o.getJSONArray("slots")
        slots = IntArray(s.length()) { s.getInt(it) }
        val sh = o.getJSONArray("shade")
        shade = Array(sh.length()) { i -> val r = sh.getJSONArray(i); IntArray(r.length()) { r.getInt(it) } }
        val lc = o.getJSONArray("lowconf")
        lowconf = IntArray(lc.length()) { lc.getInt(it) }
    }

    val edgeCount: Int get() = shade.size

    fun isLowConfidence(edge: Int): Boolean = lowconf[edge] == 1

    /** Computed sun fraction 0..1 for an edge at a local time, interpolated between slots. Outside daylight it is 0. */
    fun computedSun(edge: Int, minuteOfDay: Int): Double {
        if (minuteOfDay < slots.first() || minuteOfDay > slots.last()) return 0.0
        val idx = slots.indexOfLast { it <= minuteOfDay }.coerceAtMost(slots.size - 2)
        val f = (minuteOfDay - slots[idx]).toDouble() / (slots[idx + 1] - slots[idx])
        val a = 1.0 - shade[edge][idx] / 100.0
        val b = 1.0 - shade[edge][idx + 1] / 100.0
        return a + (b - a) * f
    }

    /**
     * Sun fraction used for routing and for the shade chip. Low-confidence segments (guessed building heights) are pushed
     * halfway towards full sun: they are neither claimed as shaded nor as fully sunny, and the claim never gets rosier.
     */
    fun sunFraction(edge: Int, minuteOfDay: Int): Double {
        val c = computedSun(edge, minuteOfDay)
        val base = if (isLowConfidence(edge)) c + (1.0 - c) * 0.5 else c
        return overlay?.invoke(edge, minuteOfDay, base)?.coerceIn(0.0, 1.0) ?: base
    }
}

