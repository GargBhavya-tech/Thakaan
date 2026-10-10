package com.thakaan.chhaya

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt

enum class Maneuver(val word: String) {
    START("Head out"), STRAIGHT("Continue straight"), SLIGHT_LEFT("Bear left"), LEFT("Turn left"), SHARP_LEFT("Turn sharp left"),
    SLIGHT_RIGHT("Bear right"), RIGHT("Turn right"), SHARP_RIGHT("Turn sharp right"), UTURN("Make a U-turn"), ARRIVE("Arrive");

    val isLeft: Boolean get() = this == SLIGHT_LEFT || this == LEFT || this == SHARP_LEFT || this == UTURN
    val isRight: Boolean get() = this == SLIGHT_RIGHT || this == RIGHT || this == SHARP_RIGHT
}

/** One manoeuvre: [atM] metres along the route, at this point. */
class NavStep(val maneuver: Maneuver, val atM: Double, val lat: Double, val lon: Double)

/** Where a position sits on the route: how far along, and how far off to the side. */
class Projection(val progressM: Double, val lateralM: Double)

/**
 * Turn-by-turn directions made from a route's polyline, for the riding screen. The road graph carries no street names, so a
 * step says what to do and how far ("Turn left in 120 m"), not which street. A turn is a change of heading of at least 35 degrees
 * measured over 15 m either side of a vertex, so a bend made of many small kinks counts once; two turns closer than 30 m count as
 * one (the sharper wins).
 */
class NavRoute(rawPoints: List<Pair<Double, Double>>) {
    val points: List<Pair<Double, Double>> = rawPoints.filterIndexed { i, p -> i == 0 || p != rawPoints[i - 1] }
    val cumM: DoubleArray
    val totalM: Double
    val steps: List<NavStep>

    init {
        val c = DoubleArray(points.size)
        for (i in 1 until points.size) c[i] = c[i - 1] + metres(points[i - 1], points[i])
        cumM = c
        totalM = if (points.isEmpty()) 0.0 else c.last()
        steps = buildSteps()
    }

    private fun buildSteps(): List<NavStep> {
        if (points.size < 2) return emptyList()
        val raw = ArrayList<Pair<Int, Double>>() // vertex index, signed heading change in degrees (positive = right)
        for (i in 1 until points.size - 1) {
            var back = i - 1
            while (back > 0 && cumM[i] - cumM[back] < WINDOW_M) back--
            var fwd = i + 1
            while (fwd < points.size - 1 && cumM[fwd] - cumM[i] < WINDOW_M) fwd++
            val d = headingChange(bearing(points[back], points[i]), bearing(points[i], points[fwd]))
            if (abs(d) >= MIN_TURN_DEG) raw += i to d
        }
        // turns closer than MERGE_M are one manoeuvre: their changes add up (two 45 degree bends make one right turn; a right then a
        // left cancel out and give no instruction), placed at the sharpest vertex of the group
        val groups = ArrayList<MutableList<Pair<Int, Double>>>()
        for (cand in raw) {
            val g = groups.lastOrNull()
            if (g != null && cumM[cand.first] - cumM[g.last().first] < MERGE_M) g += cand else groups += mutableListOf(cand)
        }
        val kept = groups.mapNotNull { g ->
            val sum = g.sumOf { it.second }
            if (abs(sum) < MIN_TURN_DEG) null else g.maxByOrNull { abs(it.second) }!!.first to sum
        }
        val out = ArrayList<NavStep>()
        out += NavStep(Maneuver.START, 0.0, points.first().first, points.first().second)
        for ((i, d) in kept) out += NavStep(classify(d), cumM[i], points[i].first, points[i].second)
        out += NavStep(Maneuver.ARRIVE, totalM, points.last().first, points.last().second)
        return out
    }

    /** The next manoeuvre ahead of [progressM] (more than a metre ahead), or null past the end. */
    fun nextStep(progressM: Double): NavStep? = steps.firstOrNull { it.atM > progressM + 1.0 }

    fun pointAt(progressM: Double): Pair<Double, Double> {
        if (points.isEmpty()) return 0.0 to 0.0
        if (points.size == 1) return points[0]
        val p = progressM.coerceIn(0.0, totalM)
        var i = 1
        while (i < points.size - 1 && cumM[i] < p) i++
        val seg = cumM[i] - cumM[i - 1]
        val t = if (seg < 1e-9) 0.0 else ((p - cumM[i - 1]) / seg).coerceIn(0.0, 1.0)
        return (points[i - 1].first + (points[i].first - points[i - 1].first) * t) to (points[i - 1].second + (points[i].second - points[i - 1].second) * t)
    }

    fun project(lat: Double, lon: Double): Projection {
        if (points.size < 2) return Projection(0.0, 0.0)
        val k = cos(Math.toRadians(lat))
        var bestLat = Double.MAX_VALUE; var bestProg = 0.0
        for (i in 1 until points.size) {
            val ax = (points[i - 1].second - lon) * k * M_PER_DEG; val ay = (points[i - 1].first - lat) * M_PER_DEG
            val bx = (points[i].second - lon) * k * M_PER_DEG; val by = (points[i].first - lat) * M_PER_DEG
            val dx = bx - ax; val dy = by - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 < 1e-9) 0.0 else ((-ax) * dx + (-ay) * dy) / len2
            val tc = t.coerceIn(0.0, 1.0)
            val d = hypot(ax + tc * dx, ay + tc * dy)
            if (d < bestLat) { bestLat = d; bestProg = cumM[i - 1] + tc * (cumM[i] - cumM[i - 1]) }
        }
        return Projection(bestProg, bestLat)
    }

    companion object {
        const val M_PER_DEG = 111_320.0
        const val WINDOW_M = 15.0
        const val MIN_TURN_DEG = 35.0
        const val MERGE_M = 30.0

        fun metres(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val k = cos(Math.toRadians((a.first + b.first) / 2))
            return hypot((b.second - a.second) * k * M_PER_DEG, (b.first - a.first) * M_PER_DEG)
        }

        /** Compass bearing in degrees, 0 = north, clockwise. */
        fun bearing(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
            val k = cos(Math.toRadians((a.first + b.first) / 2))
            val dx = (b.second - a.second) * k; val dy = b.first - a.first
            return (Math.toDegrees(atan2(dx, dy)) + 360.0) % 360.0
        }

        /** Signed change from heading [from] to [to], in -180..180; positive turns right. */
        fun headingChange(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0
            if (d < -180.0) d += 360.0
            return d
        }

        fun classify(d: Double): Maneuver {
            val a = abs(d); val right = d > 0
            return when {
                a >= 170.0 -> Maneuver.UTURN
                a >= 120.0 -> if (right) Maneuver.SHARP_RIGHT else Maneuver.SHARP_LEFT
                a >= 60.0 -> if (right) Maneuver.RIGHT else Maneuver.LEFT
                else -> if (right) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
            }
        }

        /** "now" under 25 m, 10 m steps under 100 m, 50 m steps under 1 km, then kilometres with one decimal. */
        fun distanceText(m: Double): String = when {
            m < 25.0 -> "now"
            m < 100.0 -> "${(m / 10.0).roundToInt() * 10} m"
            m < 1000.0 -> "${(m / 50.0).roundToInt() * 50} m"
            else -> "%.1f km".format(m / 1000.0)
        }

        fun instruction(step: NavStep, distanceM: Double): String {
            val d = distanceText(distanceM)
            return if (d == "now") "${step.maneuver.word} now" else "${step.maneuver.word} in $d"
        }
    }
}

/** What a simulated ride reports each second. */
class SimState(val lat: Double, val lon: Double, val speedMs: Double, val progressM: Double, val arrived: Boolean)

/**
 * A rider that follows a [NavRoute] at a made-up speed, for demos and tests indoors where there is no GPS fix: it accelerates to
 * [cruiseMs], brakes for a stop every [STOP_EVERY_M] metres (a red light, [STOP_SEC] seconds) and slows to arrive. Everything it
 * produces is SIMULATED and the screen says so.
 */
class RideSim(private val route: NavRoute, private val cruiseMs: Double = 8.0) {
    private var progress = 0.0
    private var speed = 0.0
    private var stopLeft = 0.0
    private var nextStopAt = FIRST_STOP_M

    fun step(dtSec: Double): SimState {
        val remaining = route.totalM - progress
        if (stopLeft > 0.0) {
            stopLeft -= dtSec; speed = 0.0
        } else {
            val toStop = nextStopAt - progress
            val brakeLimit = if (toStop < remaining - 40.0 && toStop >= 0.0) kotlin.math.sqrt(2.0 * DECEL * toStop) else Double.MAX_VALUE
            val arriveLimit = kotlin.math.sqrt(2.0 * DECEL * remaining.coerceAtLeast(0.0)) + 0.8
            val target = minOf(cruiseMs, brakeLimit, arriveLimit)
            speed = if (speed < target) minOf(target, speed + ACCEL * dtSec) else maxOf(target, speed - DECEL * dtSec)
            progress = (progress + speed * dtSec).coerceAtMost(route.totalM)
            if (nextStopAt - progress <= 2.0 && nextStopAt < route.totalM - 40.0) { stopLeft = STOP_SEC; nextStopAt += STOP_EVERY_M; speed = 0.0 }
        }
        val (lat, lon) = route.pointAt(progress)
        return SimState(lat, lon, speed, progress, progress >= route.totalM - 3.0)
    }

    companion object {
        const val ACCEL = 1.5
        const val DECEL = 2.5
        const val FIRST_STOP_M = 250.0
        const val STOP_EVERY_M = 350.0
        const val STOP_SEC = 12.0
    }
}
