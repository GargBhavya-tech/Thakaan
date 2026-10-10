package com.thakaan.chhaya

import com.thakaan.risk.Level
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.util.Random

class ChhayaRouterTest {
    companion object {
        lateinit var graph: RoadGraph
        lateinit var shade: ShadeLayer
        lateinit var router: ChhayaRouter

        @BeforeClass @JvmStatic fun load() {
            val dir = File("src/main/assets/chhaya")
            graph = RoadGraph(File(dir, "graph.json").readText())
            shade = ShadeLayer(File(dir, "shade.json").readText())
            router = ChhayaRouter(graph, shade)
        }

        const val AFTERNOON = 16 * 60 + 15
    }

    // two points about 3 km apart inside the demo area
    private val oLat = 12.9655; private val oLon = 77.5900
    private val dLat = 12.9850; private val dLon = 77.6100

    private fun route(level: Level, tss: Int, minute: Int = AFTERNOON, o: Pair<Double, Double> = oLat to oLon, d: Pair<Double, Double> = dLat to dLon) =
        router.route(o.first, o.second, d.first, d.second, level, tss, minute)

    @Test fun graphIsRealAndLoaded() {
        assertTrue("nodes ${graph.nodeCount}", graph.nodeCount > 1000)
        assertTrue(graph.edgeCount > 2000 && graph.arcCount >= graph.edgeCount)
        assertEquals(graph.edgeCount, shade.edgeCount)
        assertTrue(graph.pois.any { it.type == PoiType.WATER })
        assertTrue(graph.pois.all { it.accessM <= 120.0 })
    }

    @Test fun lambdaIsZeroAtCoolAndGrows() {
        assertEquals(0.0, ChhayaRouter.lambdaFor(0), 0.0)
        assertEquals(0.0, ChhayaRouter.lambdaFor(29), 0.0)
        assertTrue(ChhayaRouter.lambdaFor(55) > ChhayaRouter.lambdaFor(40))
        assertEquals(ChhayaRouter.MAX_LAMBDA, ChhayaRouter.lambdaFor(100), 1e-9)
    }

    @Test fun coolIsExactlyTheFastestRoute() {
        val r = route(Level.COOL, 10)!!
        assertEquals(0.0, r.extraSec, 1e-9)
        assertNull(r.stop)
        assertEquals(0.0, r.lambdaUsed, 0.0)
        assertTrue("3 km at urban speeds is several minutes (got ${r.timeSec})", r.timeSec in 200.0..2400.0)
    }

    @Test fun warmingAndStrainedStayInsideTheirDetourBudgets() {
        val rng = Random(11)
        var checked = 0
        repeat(25) {
            val o = rng.pick(); val d = rng.pick()
            if (graph.metresBetween(o.first, o.second, d.first, d.second) < 800) return@repeat
            val w = route(Level.WARMING, 40, o = o, d = d) ?: return@repeat
            val s = route(Level.STRAINED, 65, o = o, d = d) ?: return@repeat
            assertTrue("warming extra ${w.extraSec}", w.extraSec <= ChhayaRouter.WARMING_BUDGET_SEC + 1e-6)
            assertTrue("strained extra ${s.extraSec}", s.extraSec <= ChhayaRouter.STRAINED_BUDGET_SEC + 1e-6)
            checked++
        }
        assertTrue("only $checked pairs checked", checked >= 10)
    }

    @Test fun shadeShareNeverGetsWorseThanTheFastestRoute() {
        val rng = Random(12)
        var better = 0; var n = 0
        repeat(30) {
            val o = rng.pick(); val d = rng.pick()
            if (graph.metresBetween(o.first, o.second, d.first, d.second) < 800) return@repeat
            val fast = route(Level.COOL, 10, o = o, d = d) ?: return@repeat
            val warm = route(Level.WARMING, 45, o = o, d = d) ?: return@repeat
            assertTrue("shade ${warm.shadeShare} vs fastest ${fast.shadeShare}", warm.shadeShare >= fast.shadeShare - 1e-9)
            if (warm.shadeShare > fast.shadeShare + 0.005) better++
            n++
        }
        assertTrue(n >= 10)
        assertTrue("shade routing found a shadier alternative on $better of $n trips", better >= 1)
    }

    @Test fun threeLevelsGiveThreeDifferentBehaviours() {
        // start next to a listed water point so a stop is on offer
        val water = graph.pois.first { it.type == PoiType.WATER }
        val o = water.lat to water.lon
        val cool = route(Level.COOL, 10, o = o)!!
        val strained = route(Level.STRAINED, 65, o = o)!!
        val danger = route(Level.DANGER, 90, o = o)!!
        assertNull(cool.stop)
        assertNotNull("water stop inserted at Strained", strained.stop)
        assertEquals(PoiType.WATER, strained.stop!!.type)
        assertTrue(strained.extraSec <= ChhayaRouter.STRAINED_BUDGET_SEC + 1e-6)
        assertTrue("Danger goes to the nearest cool stop, not the destination", danger.distanceM < cool.distanceM)
        assertTrue(danger.note.startsWith("Danger"))
    }

    @Test fun dangerRoutesToAStopOrToShadeFromAnywhere() {
        val rng = Random(21)
        repeat(12) {
            val o = rng.pick()
            val r = router.route(o.first, o.second, dLat, dLon, Level.DANGER, 90, AFTERNOON)
            assertNotNull(r)
            assertTrue(r!!.stop != null || r.note.contains("nearest shade"))
        }
    }

    @Test fun routingIsFastEnoughOnThePhoneClassHardwareBudget() {
        route(Level.STRAINED, 65) // warm the JIT
        val t0 = System.nanoTime()
        repeat(5) { route(Level.STRAINED, 65) }
        val perRouteMs = (System.nanoTime() - t0) / 5e6
        assertTrue("strained route took $perRouteMs ms on the laptop", perRouteMs < 500.0)
    }

    @Test fun chipReadsExtraTimeAndShade() {
        val r = route(Level.WARMING, 45)!!
        assertTrue(r.chip.matches(Regex("\\+\\d+ min, \\d+% shade")))
    }

    @Test fun lowConfidenceShadeIsNeverRosierThanTheComputedValue() {
        var low = 0
        for (e in 0 until graph.edgeCount) {
            val computed = shade.computedSun(e, 13 * 60)
            val used = shade.sunFraction(e, 13 * 60)
            assertTrue(used >= computed - 1e-12)
            if (shade.isLowConfidence(e)) { low++; assertTrue(used >= 0.5 - 1e-12) }
        }
        assertTrue(low > 0)
    }

    @Test fun nightMeansNoSunPenalty() {
        assertEquals(0.0, shade.computedSun(0, 22 * 60), 0.0)
    }

    private fun Random.pick(): Pair<Double, Double> {
        val i = nextInt(graph.nodeCount)
        return graph.nodeLat[i] to graph.nodeLon[i]
    }
}
