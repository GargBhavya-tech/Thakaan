package com.thakaan.face

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * One reading of the phone's own temperature sensor during a forehead press (P14). [contact] is 1.0 while the screen is
 * being touched steadily and 0.0 when the touch lifts or slips.
 */
class ThermalSample(val tNanos: Long, val tempC: Double, val contact: Double = 1.0)

enum class ForeheadReason(val prompt: String) {
    TOO_SHORT("Keep the phone pressed to your forehead for the full 90 seconds"),
    NO_REST_PHASE("Hold the phone still, away from your face, for 10 seconds before pressing"),
    PHONE_WARM("The phone is already warm: let it cool, then try again"),
    PHONE_DRIFTING("The phone's temperature was changing before the press: wait and try again"),
    CONTACT_LOST("The phone lifted off your forehead: press and hold steadily"),
    NO_HEAT_SEEN("The phone did not warm up: press the screen flat against your forehead"),
    BAD_FIT("The reading was not steady: try again in a quiet place"),
}

enum class ForeheadVerdict { NOT_USABLE, NO_BASELINE, NOT_COMPARABLE, USUAL, WARMER }

/**
 * A fitted press: the sensor's rise toward the forehead's heat as a first-order curve T(t) = Tinf - (Tinf - T0) e^(-t/tau).
 * These are device-sensor numbers, not body temperature, and are only ever compared with the same worker's earlier presses
 * on the same phone.
 */
class ForeheadFit(val startC: Double, val asymptoteC: Double, val riseC: Double, val tauSec: Double, val rmseC: Double)

class ForeheadResult(
    val verdict: ForeheadVerdict,
    val reason: ForeheadReason?,
    val fit: ForeheadFit?,
) {
    /** The only text shown. It never contains a temperature (invariant 2). */
    val message: String get() = when (verdict) {
        ForeheadVerdict.NOT_USABLE -> reason?.prompt ?: "Try again"
        ForeheadVerdict.NO_BASELINE -> "Saved. A few more presses are needed before this can say anything"
        ForeheadVerdict.NOT_COMPARABLE -> "The phone started at a different temperature than usual: not compared"
        ForeheadVerdict.USUAL -> "Forehead reads about as usual for you"
        ForeheadVerdict.WARMER -> "Forehead reads warmer than usual for you: cool down and recheck"
    }
}

/** Earlier good presses by this worker on this phone. Stored on the phone only. */
class ForeheadBaseline(private val rises: List<Double> = emptyList(), private val starts: List<Double> = emptyList()) {
    init { require(rises.size == starts.size) }
    val size: Int get() = rises.size
    fun with(f: ForeheadFit) = ForeheadBaseline(rises + f.riseC, starts + f.startC)
    fun rises() = rises
    fun starts() = starts
}

/**
 * Forehead-press thermal check (P14). EXPERIMENTAL and off by default. The published FeverPhone work used a trained model on
 * clinical data to map a press to core temperature; we do not have that model, so this only fits the heat-transfer curve
 * and asks one question: is this press warmer than THIS worker's usual presses? It can raise concern and can never lower a
 * level. Until it is compared with a thermometer on real people, nothing it says is shown as a number or used by the risk
 * engine.
 */
object ForeheadThermal {
    const val MIN_SECONDS = 60.0
    const val MAX_START_C = 32.0
    /** Before the press the phone sits untouched for at least this many one-second samples. */
    const val MIN_REST_SAMPLES = 5
    /** During that rest the sensor must be steady: a steeper slope means the phone was still warming or cooling. */
    const val MAX_REST_DRIFT_C_PER_SEC = 0.03
    const val MIN_CONTACT_FRACTION = 0.9
    const val MIN_RISE_C = 0.3
    const val MAX_RMSE_C = 0.08
    const val MIN_BASELINE = 3
    const val MAX_START_MISMATCH_C = 3.0
    const val WARMER_Z = 2.5
    /** Smallest believable press-to-press spread, so a quiet baseline cannot make noise look like a fever. */
    const val SPREAD_FLOOR_C = 0.2

    private fun notUsable(r: ForeheadReason, fit: ForeheadFit? = null) = ForeheadResult(ForeheadVerdict.NOT_USABLE, r, fit)

    /** Least-squares fit for a fixed tau: the model is linear, y = a + b e^(-t/tau) with a = Tinf and b = T0 - Tinf. */
    private fun fitFor(t: DoubleArray, y: DoubleArray, tau: Double): ForeheadFit {
        val n = t.size
        var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
        val x = DoubleArray(n) { exp(-t[it] / tau) }
        for (i in 0 until n) { sx += x[i]; sy += y[i]; sxx += x[i] * x[i]; sxy += x[i] * y[i] }
        val den = n * sxx - sx * sx
        val b = if (abs(den) < 1e-12) 0.0 else (n * sxy - sx * sy) / den
        val a = (sy - b * sx) / n
        var se = 0.0
        for (i in 0 until n) { val e = y[i] - (a + b * x[i]); se += e * e }
        val t0 = a + b
        return ForeheadFit(t0, a, a - t0, tau, sqrt(se / n))
    }

    /** Samples before the first touch are the rest phase; the press is everything from the first touch on. */
    fun analyse(samples: List<ThermalSample>, baseline: ForeheadBaseline = ForeheadBaseline()): ForeheadResult {
        if (samples.size < 20) return notUsable(ForeheadReason.TOO_SHORT)
        val p = samples.indexOfFirst { it.contact >= 0.5 }
        if (p < 0) return notUsable(ForeheadReason.CONTACT_LOST)
        if (p < MIN_REST_SAMPLES) return notUsable(ForeheadReason.NO_REST_PHASE)
        val rest = samples.subList(0, p)
        val pressed = samples.subList(p, samples.size)
        val pressSec = (pressed.last().tNanos - pressed.first().tNanos) / 1e9
        if (pressSec < MIN_SECONDS) return notUsable(ForeheadReason.TOO_SHORT)
        if (pressed.map { it.contact }.average() < MIN_CONTACT_FRACTION) return notUsable(ForeheadReason.CONTACT_LOST)

        if (rest.map { it.tempC }.average() > MAX_START_C) return notUsable(ForeheadReason.PHONE_WARM)
        val restSlope = slope(DoubleArray(rest.size) { (rest[it].tNanos - rest[0].tNanos) / 1e9 }, DoubleArray(rest.size) { rest[it].tempC })
        if (abs(restSlope) > MAX_REST_DRIFT_C_PER_SEC) return notUsable(ForeheadReason.PHONE_DRIFTING)

        val t0n = pressed.first().tNanos
        val t = DoubleArray(pressed.size) { (pressed[it].tNanos - t0n) / 1e9 }
        val y = DoubleArray(pressed.size) { pressed[it].tempC }

        // best tau on a geometric grid from 15 s to 300 s
        var best: ForeheadFit? = null
        var tau = 15.0
        while (tau <= 300.0) {
            val f = fitFor(t, y, tau)
            if (best == null || f.rmseC < best.rmseC) best = f
            tau *= 1.08
        }
        val fit = best!!
        if (fit.riseC < MIN_RISE_C) return notUsable(ForeheadReason.NO_HEAT_SEEN, fit)
        if (fit.rmseC > MAX_RMSE_C) return notUsable(ForeheadReason.BAD_FIT, fit)

        if (baseline.size < MIN_BASELINE) return ForeheadResult(ForeheadVerdict.NO_BASELINE, null, fit)
        if (abs(fit.startC - median(baseline.starts())) > MAX_START_MISMATCH_C) return ForeheadResult(ForeheadVerdict.NOT_COMPARABLE, null, fit)
        val med = median(baseline.rises())
        val spread = maxOf(1.4826 * median(baseline.rises().map { abs(it - med) }), SPREAD_FLOOR_C)
        val z = (fit.riseC - med) / spread
        return ForeheadResult(if (z >= WARMER_Z) ForeheadVerdict.WARMER else ForeheadVerdict.USUAL, null, fit)
    }

    /** Least-squares slope in degrees per second. */
    private fun slope(t: DoubleArray, y: DoubleArray): Double {
        val mt = t.average(); val my = y.average()
        var num = 0.0; var den = 0.0
        for (i in t.indices) { num += (t[i] - mt) * (y[i] - my); den += (t[i] - mt) * (t[i] - mt) }
        return if (den < 1e-12) 0.0 else num / den
    }

    private fun median(x: List<Double>): Double {
        val s = x.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }
}
