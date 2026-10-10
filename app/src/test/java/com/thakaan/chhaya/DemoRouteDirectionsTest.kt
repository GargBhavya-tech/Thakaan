package com.thakaan.chhaya

import com.thakaan.risk.Level
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The demo ride on the real Bengaluru graph: the directions a rider would hear must be sensible. Also writes the first stretch to build/demo_route.txt. */
class DemoRouteDirectionsTest {
    @Test fun theDemoRouteStartsWithoutABogusUTurn() {
        val dir = File("src/main/assets/chhaya")
        val g = RoadGraph(File(dir, "graph.json").readText())
        val router = ChhayaRouter(g, ShadeLayer(File(dir, "shade.json").readText()))
        val r = router.route(12.9655, 77.5900, 12.9850, 77.6100, Level.COOL, 0, 12 * 60, nowMs = 0L, sunUp = false)!!
        val pts = ArrayList<Pair<Double, Double>>(); r.arcs.forEach { pts += g.arcPolyline(it) }
        val nav = NavRoute(pts)
        val sb = StringBuilder()
        sb.append("raw points ${pts.size}, used ${nav.points.size}, total %.0f m\n".format(nav.totalM))
        var shown = 0.0
        for (i in nav.points.indices) { if (nav.cumM[i] - shown >= 10.0 || i == 0) { sb.append("%.0f m  %.6f,%.6f\n".format(nav.cumM[i], nav.points[i].first, nav.points[i].second)); shown = nav.cumM[i] }; if (nav.cumM[i] > 400) break }
        nav.steps.take(8).forEach { sb.append("step %s at %.0f m\n".format(it.maneuver, it.atM)) }
        File("build/demo_route.txt").also { it.parentFile.mkdirs() }.writeText(sb.toString())
        val first = nav.steps.firstOrNull { it.maneuver != Maneuver.START }
        assertTrue("first instruction: ${first?.maneuver} at ${first?.atM}", first == null || first.maneuver != Maneuver.UTURN)
    }
}
