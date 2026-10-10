package com.thakaan.chhaya

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DirectionsTest {
    /** A walk from (12.90, 77.50): each leg is (compass bearing, metres), with a vertex every 20 m like a real polyline. */
    private fun walk(vararg legs: Pair<Double, Double>): List<Pair<Double, Double>> {
        var lat = 12.90; var lon = 77.50
        val pts = arrayListOf(lat to lon)
        for ((bearing, metres) in legs) {
            var left = metres
            while (left > 1e-6) {
                val m = minOf(20.0, left); left -= m
                val b = Math.toRadians(bearing)
                lat += m * cos(b) / NavRoute.M_PER_DEG
                lon += m * sin(b) / (NavRoute.M_PER_DEG * cos(Math.toRadians(lat)))
                pts += lat to lon
            }
        }
        return pts
    }

    private fun turns(r: NavRoute) = r.steps.filter { it.maneuver != Maneuver.START && it.maneuver != Maneuver.ARRIVE }

    @Test fun aStraightRoadHasNoTurns() {
        val r = NavRoute(walk(0.0 to 400.0))
        assertEquals(400.0, r.totalM, 1.0)
        assertTrue(turns(r).isEmpty())
        assertEquals(listOf(Maneuver.START, Maneuver.ARRIVE), r.steps.map { it.maneuver })
    }

    @Test fun aRightAngleToTheRightIsOneRightTurn() {
        val t = turns(NavRoute(walk(0.0 to 200.0, 90.0 to 200.0)))
        assertEquals(1, t.size)
        assertEquals(Maneuver.RIGHT, t[0].maneuver)
        assertEquals(200.0, t[0].atM, 20.0)
    }

    @Test fun aLeftTurnIsALeftTurn() {
        val t = turns(NavRoute(walk(0.0 to 200.0, 270.0 to 200.0)))
        assertEquals(listOf(Maneuver.LEFT), t.map { it.maneuver })
        assertTrue(t[0].maneuver.isLeft && !t[0].maneuver.isRight)
    }

    @Test fun sharpTurnsAndUTurnsAreNamed() {
        assertEquals(Maneuver.SHARP_RIGHT, turns(NavRoute(walk(0.0 to 200.0, 150.0 to 200.0))).single().maneuver)
        assertEquals(Maneuver.UTURN, turns(NavRoute(walk(0.0 to 200.0, 180.0 to 200.0))).single().maneuver)
        assertEquals(Maneuver.SLIGHT_LEFT, turns(NavRoute(walk(0.0 to 200.0, 320.0 to 200.0))).single().maneuver) // 40 degrees left
    }

    @Test fun aGentleCurveMadeOfSmallKinksIsNotATurn() {
        val r = NavRoute(walk(0.0 to 100.0, 8.0 to 40.0, 16.0 to 40.0, 24.0 to 40.0, 24.0 to 100.0))
        assertTrue("24 degrees in all is a curve, not a turn: ${r.steps.map { it.maneuver }}", turns(r).isEmpty())
    }

    @Test fun aSidewaysJogThatCancelsOutGivesNoInstruction() {
        val r = NavRoute(walk(0.0 to 100.0, 90.0 to 12.0, 0.0 to 100.0))
        assertTrue("right then left within a few metres: ${r.steps.map { it.maneuver }}", turns(r).isEmpty())
    }

    @Test fun twoBendsCloseTogetherAddUpToOneTurn() {
        val t = turns(NavRoute(walk(0.0 to 100.0, 45.0 to 15.0, 90.0 to 100.0)))
        assertEquals(1, t.size)
        assertEquals(Maneuver.RIGHT, t[0].maneuver)
    }

    @Test fun aHookInTheFirstFortyMetresIsNotAnnounced() {
        val r = NavRoute(walk(90.0 to 15.0, 270.0 to 15.0, 0.0 to 200.0))
        assertTrue("a stub that doubles back at the start gives no instruction: ${r.steps.map { it.maneuver }}", turns(r).isEmpty())
    }

    @Test fun aRouteThatLoopsBackToItsStartBeginsWhereItReturns() {
        val r = NavRoute(walk(90.0 to 100.0, 270.0 to 100.0, 0.0 to 300.0))
        assertTrue("the stub is dropped, the road north is the route: ${r.totalM}", r.totalM in 285.0..320.0)
        assertTrue("and there is no U-turn: ${r.steps.map { it.maneuver }}", turns(r).isEmpty())
    }

    @Test fun aRouteThatDoesNotComeBackIsLeftAlone() {
        val r = NavRoute(walk(0.0 to 200.0, 90.0 to 200.0))
        assertEquals(400.0, r.totalM, 5.0)
    }

    @Test fun nextStepLooksAhead() {
        val r = NavRoute(walk(0.0 to 200.0, 90.0 to 200.0))
        assertEquals(Maneuver.RIGHT, r.nextStep(50.0)!!.maneuver)
        assertEquals(Maneuver.ARRIVE, r.nextStep(260.0)!!.maneuver)
        assertNull(r.nextStep(r.totalM))
    }

    @Test fun projectionGivesProgressAndHowFarOffTheRoadItIs() {
        val r = NavRoute(walk(0.0 to 400.0))
        val onRoad = r.pointAt(150.0)
        val p = r.project(onRoad.first, onRoad.second)
        assertEquals(150.0, p.progressM, 1.0)
        assertEquals(0.0, p.lateralM, 0.5)
        val off = r.project(onRoad.first, onRoad.second + 30.0 / (NavRoute.M_PER_DEG * cos(Math.toRadians(12.9))))
        assertEquals(30.0, off.lateralM, 1.0)
        assertEquals(150.0, off.progressM, 1.0)
    }

    @Test fun theTextSaysWhatAndHowFar() {
        val r = NavRoute(walk(0.0 to 200.0, 270.0 to 200.0))
        val step = r.steps.first { it.maneuver == Maneuver.LEFT }
        assertEquals("Turn left in 150 m", NavRoute.instruction(step, 148.0))
        assertEquals("Turn left in 60 m", NavRoute.instruction(step, 62.0))
        assertEquals("Turn left now", NavRoute.instruction(step, 10.0))
        assertEquals("1.3 km", NavRoute.distanceText(1280.0))
    }

    @Test fun aSimulatedRideArrivesStopsAtLightsAndNeverGoesBackwards() {
        val r = NavRoute(walk(0.0 to 400.0, 90.0 to 500.0, 0.0 to 300.0))
        val sim = RideSim(r)
        var last = -1.0; var stops = 0; var wasMoving = false; var arrivedAt = -1; var maxSpeed = 0.0
        for (t in 0 until 600) {
            val s = sim.step(1.0)
            assertTrue("progress never goes back", s.progressM >= last - 1e-9); last = s.progressM
            maxSpeed = maxOf(maxSpeed, s.speedMs)
            if (s.speedMs < 0.2 && wasMoving && !s.arrived) stops++
            wasMoving = s.speedMs > 2.0
            if (s.arrived && arrivedAt < 0) arrivedAt = t
        }
        assertTrue("arrives", arrivedAt in 1..599)
        assertTrue("stops for at least one red light on a 1.2 km ride: $stops", stops >= 1)
        assertTrue("never faster than cruise: $maxSpeed", maxSpeed <= 8.0 + 1e-6)
        assertFalse(sim.step(1.0).progressM > r.totalM + 1e-6)
        assertNotNull(r.nextStep(0.0))
    }
}
