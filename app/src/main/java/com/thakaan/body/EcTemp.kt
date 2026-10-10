package com.thakaan.body

import kotlin.math.exp
import kotlin.math.pow

/**
 * ECTemp: core-temperature trend from heart rate (Buller et al. 2013), an extended Kalman filter stepped once per minute.
 *
 * The arithmetic of [step] is written in exactly the same order as the Python and TypeScript twins so that
 * assets/ectemp_vectors.json matches to 1e-9. Do not "tidy" it (a*ct*ct, never ct**2).
 *
 * Two observation models share one filter:
 *  - [PublishedQuadratic]: the original curve. Used with personalisation OFF; the 7 reference vectors are defined on it.
 *  - [SigmoidModel]: the Thakaan v3 curve with a per-worker floor A and ceiling K. Used once personalised.
 *
 * Never shown to the worker as a temperature (invariant 2). Core estimates lose sensitivity above ~39 C, so actions
 * fire at 38.5 C and an independent HR rule backs this up (invariant 4).
 */
object EcTempParams {
    const val GAMMA2 = 0.000484 // process noise Q (0.022 C per minute)
    const val SIGMA2 = 356.4544 // observation noise R (18.88 bpm)
    const val CT0 = 37.1
    const val V0 = 0.02
    const val V0_HOT = 0.2
    const val HR_MIN = 40.0
    const val HR_MAX = 210.0
    const val STEP_SECONDS = 60L
    const val NIS_GATE = 3.84 // chi-square 95%, 1 dof

    // published quadratic: h(ct) = a ct^2 + b ct + c
    const val A = -4.5714
    const val B = 384.4286
    const val C = -7887.1
}

/** Expected heart rate as a function of core temperature, and its slope (the EKF linearisation M). */
interface ObservationModel {
    fun h(ct: Double): Double
    fun slope(ct: Double): Double
}

object PublishedQuadratic : ObservationModel {
    override fun h(ct: Double) = EcTempParams.A * ct * ct + EcTempParams.B * ct + EcTempParams.C
    override fun slope(ct: Double) = 2 * EcTempParams.A * ct + EcTempParams.B
}

/**
 * HRexp = A + (K - A) / (1 + 0.06 e^{-0.89 (ct - 37.84)})^{1/0.07}
 * With u = 1 + 0.06 e^{-0.89 (ct - 37.84)} and p = 1/0.07:  dHR/dct = (K - A) p 0.89 (u - 1) u^{-p-1}   (always > 0).
 */
class SigmoidModel(val floorA: Double, val ceilingK: Double) : ObservationModel {
    private fun u(ct: Double) = 1.0 + 0.06 * exp(-0.89 * (ct - 37.84))
    override fun h(ct: Double) = floorA + (ceilingK - floorA) / u(ct).pow(P)
    override fun slope(ct: Double): Double {
        val u = u(ct)
        return (ceilingK - floorA) * P * 0.89 * (u - 1.0) * u.pow(-P - 1.0)
    }

    private companion object {
        const val P = 1.0 / 0.07
    }
}

data class EcState(val ct: Double, val v: Double)

data class StepResult(val state: EcState, val accepted: Boolean?, val nis: Double?)

object EcTemp {
    /** One minute of the plain filter (no gate). hr == null runs predict-only. Mirrors the reference twins exactly. */
    fun step(
        ct: Double,
        v: Double,
        hr: Double?,
        model: ObservationModel = PublishedQuadratic,
        q: Double = EcTempParams.GAMMA2,
        r: Double = EcTempParams.SIGMA2,
    ): EcState {
        val vPred = v + q
        if (hr == null) return EcState(ct, vPred)
        val m = model.slope(ct)
        val k = vPred * m / (m * m * vPred + r)
        return EcState(ct + k * (hr - model.h(ct)), (1 - k * m) * vPred)
    }

    /**
     * One minute with the normalised-innovation-squared gate: (hr - h)^2 / (m^2 v + R) > 3.84 rejects the reading,
     * and a rejected reading is handled as a missing minute, so variance grows and the estimate does not jump.
     */
    fun stepGated(
        ct: Double,
        v: Double,
        hr: Double?,
        model: ObservationModel,
        q: Double = EcTempParams.GAMMA2,
        r: Double = EcTempParams.SIGMA2,
        gate: Double = EcTempParams.NIS_GATE,
    ): StepResult {
        if (hr == null || hr < EcTempParams.HR_MIN || hr > EcTempParams.HR_MAX) {
            return StepResult(step(ct, v, null, model, q, r), accepted = null, nis = null)
        }
        val vPred = v + q
        val m = model.slope(ct)
        val innovation = hr - model.h(ct)
        val nis = innovation * innovation / (m * m * vPred + r)
        if (nis > gate) return StepResult(step(ct, v, null, model, q, r), accepted = false, nis = nis)
        return StepResult(step(ct, v, hr, model, q, r), accepted = true, nis = nis)
    }

    /** Where sustained [hr] leads for the published curve: the physiological root of h(ct) = hr. Null above the peak. */
    fun steadyStateCt(hr: Double): Double? {
        val disc = EcTempParams.B * EcTempParams.B - 4 * EcTempParams.A * (EcTempParams.C - hr)
        if (disc < 0) return null
        return (-EcTempParams.B + kotlin.math.sqrt(disc)) / (2 * EcTempParams.A)
    }

    /** The minute's input: median of samples inside [40, 210] bpm, or null if none survive (motion spikes are dropped). */
    fun minuteMedian(samples: List<Double>): Double? {
        val valid = samples.filter { it in EcTempParams.HR_MIN..EcTempParams.HR_MAX }.sorted()
        if (valid.isEmpty()) return null
        val mid = valid.size / 2
        return if (valid.size % 2 == 1) valid[mid] else (valid[mid - 1] + valid[mid]) / 2
    }

    /** Run a series of minute heart rates from ([ct0], [v0]); returns the state after each minute. */
    fun run(hrs: List<Double?>, ct0: Double = EcTempParams.CT0, v0: Double = EcTempParams.V0): List<EcState> {
        var s = EcState(ct0, v0)
        return hrs.map { hr -> step(s.ct, s.v, hr).also { s = it } }
    }
}

/** Per-worker curve: floor A from the lowest sustained 5-minute rest HR, ceiling K from age (Tanaka HRmax = 208 - 0.7 age). */
data class Profile(val ageYears: Int, val lowestRestHr: Double? = null) {
    val hrMax: Double get() = 208.0 - 0.7 * ageYears
    fun model(): SigmoidModel = SigmoidModel(floorA = (lowestRestHr ?: DEFAULT_FLOOR).coerceIn(40.0, 90.0), ceilingK = hrMax)

    companion object {
        /** Used until onboarding has seen a sustained rest HR. */
        const val DEFAULT_FLOOR = 60.0
    }
}

/** Start-of-shift rules (#23). */
data class ShiftStart(val ct0: Double, val v0: Double, val qFactor: Double, val inflateMinutes: Int) {
    companion object {
        const val INFLATE_MINUTES = 20
        const val INFLATE_FACTOR = 3.0 // assumption: the Build Map says "inflated" without a number

        /** Core temperature has a daily rhythm: 36.8 C at 05:00 rising to 37.4 C at 17:00, falling back overnight. */
        fun circadianCt(hourOfDay: Double): Double {
            val h = ((hourOfDay % 24) + 24) % 24
            return if (h in 5.0..17.0) 36.8 + 0.6 * (h - 5.0) / 12.0 else {
                val sinceSeventeen = if (h >= 17.0) h - 17.0 else h + 7.0
                37.4 - 0.6 * sinceSeventeen / 12.0
            }
        }

        /** Larger starting variance when the worker logs on already hot (fingertip HR > 100 or WBGT > 30 C). */
        fun of(hourOfDay: Double, fingertipHr: Double?, wbgtC: Double?): ShiftStart {
            val hot = (fingertipHr != null && fingertipHr > 100.0) || (wbgtC != null && wbgtC > 30.0)
            return ShiftStart(
                ct0 = circadianCt(hourOfDay),
                v0 = if (hot) EcTempParams.V0_HOT else EcTempParams.V0,
                qFactor = INFLATE_FACTOR,
                inflateMinutes = INFLATE_MINUTES,
            )
        }
    }
}

/**
 * Stateful filter for the app. Time-aware: readings arrive at stops, not every minute, so any whole minutes between
 * two updates run predict-only and the variance grows linearly with the gap (it never shrinks across a gap).
 */
class EcTempFilter(
    private val model: ObservationModel = PublishedQuadratic,
    private val gated: Boolean = false,
    start: ShiftStart = ShiftStart(EcTempParams.CT0, EcTempParams.V0, 1.0, 0),
    private val r: Double = EcTempParams.SIGMA2,
) {
    var ct: Double = start.ct0
        private set
    var v: Double = start.v0
        private set
    var lastAccepted: Boolean? = null
        private set
    var lastNis: Double? = null
        private set

    private val qFactor = start.qFactor
    private val inflateMinutes = start.inflateMinutes
    private var minutes = 0
    private var lastMinuteIndex: Long? = null

    private fun q(): Double = if (minutes < inflateMinutes) EcTempParams.GAMMA2 * qFactor else EcTempParams.GAMMA2

    /**
     * Optional heat-balance prior (#24): given the current core estimate it returns this minute's increment in degrees.
     * Null (the default) keeps the plain published filter, which is what the 7 reference vectors exercise.
     */
    var prior: ((Double) -> Double)? = null

    private fun advanceOne(hr: Double?) {
        prior?.let { ct += it(ct) }
        if (gated) {
            val res = EcTemp.stepGated(ct, v, hr, model, q(), r)
            ct = res.state.ct; v = res.state.v; lastAccepted = res.accepted; lastNis = res.nis
        } else {
            val s = EcTemp.step(ct, v, hr, model, q(), r)
            ct = s.ct; v = s.v; lastAccepted = if (hr == null) null else true; lastNis = null
        }
        minutes++
    }

    /** Feed the minute heart rate observed at [epochSeconds] (null = no reading this minute). */
    fun update(epochSeconds: Long, hr: Double?) {
        val idx = epochSeconds / EcTempParams.STEP_SECONDS
        val last = lastMinuteIndex
        if (last != null) {
            require(idx > last) { "time must move forward" }
            repeat((idx - last - 1).toInt()) { advanceOne(null) } // the skipped minutes: predict only
        }
        advanceOne(hr)
        lastMinuteIndex = idx
    }
}
