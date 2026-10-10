package com.thakaan.chhaya

import com.thakaan.risk.Level
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * Measures (not asserts) ticket #60's route row on the real Bengaluru graph: for random origin-destination pairs 1.5 to 4 km
 * apart, how much longer is the strain-aware route than the fastest one, and how much more shade does it have, at each level.
 * Two seeds are run so the table shows the numbers reproduce. Results go to build/metrics_routes.txt. These are
 * DEVELOPMENT-TIME numbers (shade is modelled, the pairs are random, nobody walked them).
 */
class RouteMetricsTest {
    private fun pairs(g: RoadGraph, seed: Long, n: Int): List<DoubleArray> {
        val rnd = Random(seed); val out = ArrayList<DoubleArray>()
        while (out.size < n) {
            val a = rnd.nextInt(g.nodeCount); val b = rnd.nextInt(g.nodeCount)
            val d = g.metresBetween(g.nodeLat[a], g.nodeLon[a], g.nodeLat[b], g.nodeLon[b])
            if (d in 1500.0..4000.0) out += doubleArrayOf(g.nodeLat[a], g.nodeLon[a], g.nodeLat[b], g.nodeLon[b])
        }
        return out
    }

    private fun median(v: List<Double>) = if (v.isEmpty()) Double.NaN else v.sorted()[v.size / 2]

    @Test fun measureRouteExtraTimeAndShadeGainPerLevel() {
        val dir = File("src/main/assets/chhaya")
        val g = RoadGraph(File(dir, "graph.json").readText())
        val sb = StringBuilder()
        val levels = listOf(Level.COOL to 10, Level.WARMING to 42, Level.STRAINED to 65)
        for ((name, file) in listOf("OSM shade" to "shade.json", "Satellite shade (equinox)" to "shade_obc_79.json", "Satellite shade (summer solstice)" to "shade_obc_172.json")) {
            val router = ChhayaRouter(g, ShadeLayer(File(dir, file).readText()))
            for (seed in listOf(1L, 2L)) {
                val ps = pairs(g, seed, 150)
                for (minute in listOf(11 * 60, 14 * 60, 16 * 60 + 15)) for ((level, tss) in levels) {
                    val extra = ArrayList<Double>(); val extraPct = ArrayList<Double>(); val gain = ArrayList<Double>(); val base = ArrayList<Double>(); var changed = 0; var found = 0
                    val t0 = System.nanoTime()
                    for (p in ps) {
                        val r = router.route(p[0], p[1], p[2], p[3], level, tss, minute, nowMs = 0L) ?: continue
                        found++
                        base += 100.0 * r.fastestShadeShare
                        extra += r.extraSec; extraPct += 100.0 * r.extraSec / r.fastestTimeSec; gain += 100.0 * (r.shadeShare - r.fastestShadeShare)
                        if (r.arcs != r.fastestArcs) changed++
                    }
                    val ms = (System.nanoTime() - t0) / 1e6 / ps.size
                    sb.append("%s | seed %d | %02d:%02d | %-8s | routes %d/%d | changed %3.0f%% | fastest route shade median %.0f%% | extra median %.0f s (%.1f%%) | shade gain median %+.1f pts, mean %+.1f pts | %.1f ms/route\n".format(
                        name, seed, minute / 60, minute % 60, level.word, found, ps.size, 100.0 * changed / found, median(base), median(extra), median(extraPct), median(gain), gain.average(), ms))
                }
            }
        }
        val out = File("build/metrics_routes.txt"); out.parentFile.mkdirs(); out.writeText(sb.toString())
        assertTrue(sb.isNotEmpty())
    }
}
