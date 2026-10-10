package com.thakaan.chhaya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random
import kotlin.math.cos

class LightMapTest {
    companion object {
        lateinit var graph: RoadGraph
        lateinit var json: String
        lateinit var shadeJson: String

        @BeforeClass @JvmStatic fun load() {
            val dir = File("src/main/assets/chhaya")
            json = File(dir, "graph.json").readText()
            shadeJson = File(dir, "shade.json").readText()
            graph = RoadGraph(json)
        }
        const val MIN = 15 * 60
    }

    /** Points every ~5 m along one edge, with gaussian GPS noise. */
    private fun walkEdge(e: Int, noiseM: Double, rng: Random, sun: Boolean?, t0: Long = 0L): List<ShadeObservation> {
        val g = graph.edgeGeom[e]
        val pts = (0 until g.size step 2).map { g[it] / 1e6 to g[it + 1] / 1e6 }
        val out = ArrayList<ShadeObservation>()
        var t = t0
        for (i in 0 until pts.size - 1) {
            val len = graph.metresBetween(pts[i].first, pts[i].second, pts[i + 1].first, pts[i + 1].second)
            val steps = maxOf(1, (len / 5).toInt())
            for (s in 0 until steps) {
                val f = s.toDouble() / steps
                val la = pts[i].first + (pts[i + 1].first - pts[i].first) * f
                val lo = pts[i].second + (pts[i + 1].second - pts[i].second) * f
                val k = 111_320.0
                out += ShadeObservation(t, la + rng.nextGaussian() * noiseM / k, lo + rng.nextGaussian() * noiseM / (k * cos(Math.toRadians(la))), sun, 8.0)
                t += 1000
            }
        }
        return out
    }

    private fun longSunnyEdge(layer: ShadeLayer): Int =
        (0 until graph.edgeCount).filter { graph.edgeLenM[it] in 80.0..250.0 && layer.sunFraction(it, MIN) > 0.9 && !layer.isLowConfidence(it) }
            .maxByOrNull { graph.edgeLenM[it] } ?: (0 until graph.edgeCount).first { graph.edgeLenM[it] in 80.0..250.0 && layer.sunFraction(it, MIN) > 0.6 }

    @Test fun noisyWalkIsMatchedToTheRightEdgesMostOfTheTime() {
        val layer = ShadeLayer(shadeJson)
        val e = longSunnyEdge(layer)
        val obs = walkEdge(e, 8.0, Random(1), true)
        val matched = MapMatcher(graph).match(obs.map { it.lat to it.lon })
        val share = matched.count { it == e }.toDouble() / matched.size
        assertTrue("matched share $share over ${matched.size} points", share >= 0.7)
    }

    @Test fun walkingTheStreetTwiceMovesItsShadeEstimateTowardTheMeasurement() {
        val layer = ShadeLayer(shadeJson)
        val e = longSunnyEdge(layer)
        val before = layer.sunFraction(e, MIN)
        assertTrue("setup: computed layer says sunny ($before)", before > 0.6)
        val belief = ShadeBelief(layer); belief.install()
        val agg = LightMapAggregator(graph, belief)
        val rng = Random(2)
        // two passes on different days, both measured in shade
        val a = agg.ingest(walkEdge(e, 6.0, rng, false), { MIN })
        val mid = layer.sunFraction(e, MIN)
        val b = agg.ingest(walkEdge(e, 6.0, rng, false, t0 = 86_400_000L), { MIN })
        val after = layer.sunFraction(e, MIN)
        assertTrue("passes accepted $a $b", a >= 1 && b >= 1)
        assertTrue("estimate $before -> $mid -> $after moves toward shade", before > mid && mid > after)
        assertTrue("two passes shift it by a clear margin", before - after > 0.15)
    }

    @Test fun unknownReadingsAndPoorFixesNeverChangeTheLayer() {
        val layer = ShadeLayer(shadeJson)
        val e = longSunnyEdge(layer)
        val before = layer.sunFraction(e, MIN)
        val belief = ShadeBelief(layer); belief.install()
        val agg = LightMapAggregator(graph, belief)
        val unknown = walkEdge(e, 6.0, Random(3), null)
        assertEquals(0, agg.ingest(unknown, { MIN }))
        val poor = walkEdge(e, 6.0, Random(3), false).map { ShadeObservation(it.tMillis, it.lat, it.lon, it.sun, 80.0) }
        assertEquals(0, agg.ingest(poor, { MIN }))
        assertEquals(before, layer.sunFraction(e, MIN), 1e-12)
    }

    @Test fun aSinglePointNeverMovesAnything() {
        val layer = ShadeLayer(shadeJson)
        val e = longSunnyEdge(layer)
        val before = layer.sunFraction(e, MIN)
        val belief = ShadeBelief(layer); belief.install()
        val one = walkEdge(e, 4.0, Random(4), false).take(1)
        assertEquals(0, LightMapAggregator(graph, belief).ingest(one, { MIN }))
        assertEquals(before, layer.sunFraction(e, MIN), 1e-12)
    }

    @Test fun measurementsOnlyAffectTheirOwnTimeSlot() {
        val layer = ShadeLayer(shadeJson)
        val e = longSunnyEdge(layer)
        val other = layer.sunFraction(e, 9 * 60)
        val belief = ShadeBelief(layer); belief.install()
        LightMapAggregator(graph, belief).ingest(walkEdge(e, 5.0, Random(5), false), { MIN })
        assertEquals(other, layer.sunFraction(e, 9 * 60), 1e-12)
    }

    @Test fun routerSeesTheCorrection() {
        val layer = ShadeLayer(shadeJson)
        val e = longSunnyEdge(layer)
        val belief = ShadeBelief(layer); belief.install()
        repeat(3) { LightMapAggregator(graph, belief).ingest(walkEdge(e, 5.0, Random(6L + it), false), { MIN }) }
        assertTrue(layer.sunFraction(e, MIN) < 0.6)
    }
}
