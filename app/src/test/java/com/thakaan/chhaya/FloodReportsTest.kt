package com.thakaan.chhaya

import com.thakaan.risk.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class FloodReportsTest {
    companion object {
        lateinit var graph: RoadGraph
        lateinit var shade: ShadeLayer

        @BeforeClass @JvmStatic fun load() {
            val dir = File("src/main/assets/chhaya")
            graph = RoadGraph(File(dir, "graph.json").readText())
            shade = ShadeLayer(File(dir, "shade.json").readText())
        }

        const val AFTERNOON = 16 * 60 + 15
        const val NOW = 1_800_000_000_000L
    }

    private val o = 12.9655 to 77.5900
    private val d = 12.9850 to 77.6100

    private fun base(floods: FloodReports?) = ChhayaRouter(graph, shade, floods).route(o.first, o.second, d.first, d.second, Level.COOL, 10, AFTERNOON, NOW)!!

    /** A point in the middle of the middle edge of the plain fastest route. */
    private fun middleOf(r: Route): Triple<Int, Double, Double> {
        val arc = r.arcs[r.arcs.size / 2]
        val line = graph.arcPolyline(arc)
        val p = line[line.size / 2]
        return Triple(graph.arcEdge[arc], p.first, p.second)
    }

    @Test fun aReportFindsTheRoadItWasMadeOn() {
        val r = base(null)
        val (edge, lat, lon) = middleOf(r)
        assertEquals(edge, graph.nearestEdge(lat, lon, 30.0))
    }

    @Test fun aPointFarFromAnyRoadFindsNoEdge() {
        assertNull(graph.nearestEdge(12.0, 70.0, 60.0))
    }

    @Test fun aFloodReportReroutesTheRide() {
        val before = base(null)
        val (edge, lat, lon) = middleOf(before)
        val floods = FloodReports()
        val res = floods.report(graph, lat, lon, NOW, photoProblem = null)
        assertTrue(res.message, res.accepted)
        val after = base(floods)
        assertFalse("the flooded road is still on the route", after.arcs.any { graph.arcEdge[it] == edge })
        assertEquals(0, after.floodedEdges)
        assertTrue("the detour cannot be quicker than the fastest route", after.timeSec >= before.timeSec - 1e-6)
    }

    @Test fun theReportExpiresAndTheOriginalRouteComesBack() {
        val before = base(null)
        val (_, lat, lon) = middleOf(before)
        val floods = FloodReports()
        floods.report(graph, lat, lon, NOW, null)
        val later = ChhayaRouter(graph, shade, floods).route(o.first, o.second, d.first, d.second, Level.COOL, 10, AFTERNOON, NOW + FloodReports.TTL_MS + 1)!!
        assertEquals(before.arcs, later.arcs)
    }

    @Test fun aRejectedPhotoClosesNothing() {
        val before = base(null)
        val (_, lat, lon) = middleOf(before)
        val floods = FloodReports()
        val res = floods.report(graph, lat, lon, NOW, FloodPhotoCheck.problem(60_000, 120.0, 30.0))
        assertFalse(res.accepted)
        assertTrue(floods.active(NOW).isEmpty())
        assertEquals(before.arcs, base(floods).arcs)
    }

    @Test fun ifTheOnlyWayIsThroughTheFloodTheRouteSaysSo() {
        // close every road touching the destination's node: no way to arrive except through a flooded edge
        val dn = graph.nearestNode(d.first, d.second)
        val floods = FloodReports()
        graph.forIn(dn) { a ->
            val line = graph.arcPolyline(a)
            val p = line[line.size / 2]
            floods.report(graph, p.first, p.second, NOW, null)
        }
        val r = base(floods)
        assertTrue("flooded edges ${r.floodedEdges}", r.floodedEdges > 0)
        assertTrue(r.note, r.note.contains("flooded"))
    }

    @Test fun reportsSurviveARestartThroughJson() {
        val floods = FloodReports()
        val (_, lat, lon) = middleOf(base(null))
        floods.report(graph, lat, lon, NOW, null)
        val back = FloodReports.fromJson(floods.toJson())
        assertEquals(floods.closedEdges(NOW), back.closedEdges(NOW))
        assertTrue(FloodReports.fromJson("not json").active(NOW).isEmpty())
    }

    @Test fun photoChecksRejectOldDarkAndBlankPictures() {
        assertNull(FloodPhotoCheck.problem(1_000, 90.0, 25.0))
        assertNotNull(FloodPhotoCheck.problem(30_000, 90.0, 25.0))
        assertNotNull(FloodPhotoCheck.problem(1_000, 4.0, 25.0))
        assertNotNull(FloodPhotoCheck.problem(1_000, 90.0, 1.0))
    }

    @Test fun rainCueNeedsDaylightAndLightDropOrAVeryDampSky() {
        assertEquals(RainCue.Level.LIKELY, RainCue.detect(40.0, 88.0, 60.0, 8_000.0, 30_000.0))
        assertEquals(RainCue.Level.POSSIBLE, RainCue.detect(40.0, 50.0, 40.0, 8_000.0, 30_000.0))
        assertEquals(RainCue.Level.NONE, RainCue.detect(40.0, 50.0, 40.0, 28_000.0, 30_000.0))
        assertEquals(RainCue.Level.NONE, RainCue.detect(3.0, 95.0, 95.0, 100.0, 2_500.0))
    }

    @Test fun aReportBlocksEveryRoadWithinThirtyMetresButNotFarRoads() {
        val (edge, lat, lon) = middleOf(base(null))
        val floods = FloodReports()
        val res = floods.report(graph, lat, lon, NOW, null)
        val closed = floods.closedEdges(NOW)
        assertTrue(edge in closed)
        assertEquals(graph.edgesWithin(lat, lon, FloodReports.BLOCK_RADIUS_M) + edge, closed)
        assertTrue("a flooded patch should also close the road beside it", closed.size >= 1)
        val far = graph.edgesWithin(lat, lon, 400.0) - graph.edgesWithin(lat, lon, 60.0)
        assertTrue("roads far away stay open", far.isNotEmpty() && far.none { it in closed })
        assertTrue(res.accepted)
    }

    @Test fun savedReportsKeepAllBlockedEdgesAndOldOnesStillLoad() {
        val (_, lat, lon) = middleOf(base(null))
        val floods = FloodReports(); floods.report(graph, lat, lon, NOW, null)
        assertEquals(floods.closedEdges(NOW), FloodReports.fromJson(floods.toJson()).closedEdges(NOW))
        val old = FloodReports.fromJson("[{\"e\":5,\"lat\":12.97,\"lon\":77.59,\"at\":$NOW,\"until\":${NOW + 1000}}]")
        assertEquals(setOf(5), old.closedEdges(NOW))
    }
}
