package com.thakaan.body

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class Anthropometrics(val massKg: Double, val heightM: Double) {
    /** DuBois body surface area, m^2. */
    val bsa: Double get() = 0.202 * massKg.pow(0.425) * heightM.pow(0.725)
}

/** Air temperature, mean radiant temperature, humidity and air speed around the rider. */
class ThermalEnv(val airC: Double, val mrtC: Double, val rhPercent: Double, val airSpeedMs: Double, val pressureKpa: Double = 101.3) {
    companion object {
        /**
         * Mean radiant temperature from a globe thermometer (ISO 7726, 150 mm black globe, forced convection). The
         * Liljegren globe temperature from lwbgt feeds this. Never the phone's own body temperature.
         */
        fun mrtFromGlobe(globeC: Double, airC: Double, airSpeedMs: Double): Double {
            val v = max(airSpeedMs, 0.1)
            val tk = ((globeC + 273.15).pow(4) + 1.1e8 * v.pow(0.6) / (0.95 * 0.15.pow(0.4)) * (globeC - airC)).pow(0.25)
            return tk - 273.15
        }

        /** Fallback without a globe: shortwave absorbed by the body adds to the air temperature (assumption, see DECISIONS.md). */
        fun mrtFromIrradiance(airC: Double, irradianceWm2: Double, sunFraction: Double): Double =
            airC + 0.0225 * irradianceWm2 * sunFraction
    }
}

class GaggeState(val tcr: Double, val tsk: Double)

class GaggeStep(
    val state: GaggeState,
    /** Regulatory sweat production, g per m^2 per hour. */
    val sweatGPerM2h: Double,
    val evapW: Double,
    val skinWettedness: Double,
)

/**
 * Gagge two-node model (core + skin) stepped in time, after the classic formulation (Gagge, Stolwijk & Nishi 1971).
 * Inputs per step: metabolic rate, environment and clothing. This is a PRIOR for ECTemp, not a measurement: it moves the
 * core trend between heart readings and can be switched off.
 */
object Gagge {
    private const val TCR_N = 36.8
    private const val TSK_N = 33.7
    private const val ALPHA = 0.1 // skin share of body mass
    private const val CP = 3492.0 // J/(kg K)
    private const val HR_RAD = 4.7 // W/(m^2 K)

    fun psatKpa(tC: Double): Double = exp(18.6686 - 4030.183 / (tC + 235.0))

    fun step(s: GaggeState, env: ThermalEnv, metW: Double, clo: Double, body: Anthropometrics, dtSec: Double = 60.0): GaggeStep {
        val bsa = body.bsa
        val m = metW / bsa
        val pa = env.rhPercent / 100.0 * psatKpa(env.airC)
        val hc = max(3.0, 8.6 * max(env.airSpeedMs, 0.05).pow(0.53))
        val h = hc + HR_RAD
        val to = (HR_RAD * env.mrtC + hc * env.airC) / h
        val rcl = 0.155 * clo
        val fcl = 1.0 + 0.15 * clo
        val he = 16.5 * hc
        val reCl = rcl / (0.45 * 0.0165) / 1000.0 // m^2 kPa/W, vapour resistance of the clothing
        val capCore = (1 - ALPHA) * body.massKg * CP / bsa
        val capSkin = ALPHA * body.massKg * CP / bsa
        val tbN = ALPHA * TSK_N + (1 - ALPHA) * TCR_N

        var tcr = s.tcr; var tsk = s.tsk
        var sweat = 0.0; var evap = 0.0; var wet = 0.06
        val sub = 15.0
        var t = 0.0
        while (t < dtSec - 1e-9) {
            val dt = min(sub, dtSec - t)
            val warmC = max(0.0, tcr - TCR_N)
            val warmS = max(0.0, tsk - TSK_N)
            val coldS = max(0.0, TSK_N - tsk)
            val tb = ALPHA * tsk + (1 - ALPHA) * tcr
            val warmB = max(0.0, tb - tbN)
            val skbf = ((6.3 + 200.0 * warmC) / (1.0 + 0.5 * coldS)).coerceIn(0.5, 90.0)
            sweat = (170.0 * warmB * exp(warmS / 10.7)).coerceIn(0.0, 500.0)
            val ersw = 0.68 * sweat
            val kTot = 5.28 + 1.163 * skbf
            val qDry = (tsk - to) / (rcl + 1.0 / (fcl * h))
            val pskS = psatKpa(tsk)
            val emax = max(1e-6, (pskS - pa) / (reCl + 1.0 / (fcl * he)))
            wet = (0.06 + 0.94 * ersw / emax).coerceIn(0.06, 1.0)
            evap = wet * emax
            val qRes = 0.0014 * m * (34.0 - env.airC) + 0.0173 * m * (5.87 - pa)
            val dCore = (m - qRes - kTot * (tcr - tsk)) / capCore
            val dSkin = (kTot * (tcr - tsk) - qDry - evap) / capSkin
            tcr += dCore * dt
            tsk += dSkin * dt
            t += dt
        }
        return GaggeStep(GaggeState(tcr, tsk.coerceIn(20.0, 40.0)), sweat, evap, wet)
    }
}

/** Simplified ISO 7933 style predicted sweat rate: required evaporation, evaporative efficiency, capped at a maximum. */
object Phs {
    class Result(val requiredSweatGPerH: Double, val capped: Boolean)

    fun sweatRate(env: ThermalEnv, metW: Double, clo: Double, body: Anthropometrics, acclimatised: Boolean): Result {
        val bsa = body.bsa
        val m = metW / bsa
        val tsk = 34.0
        val pa = env.rhPercent / 100.0 * Gagge.psatKpa(env.airC)
        val hc = max(3.0, 8.6 * max(env.airSpeedMs, 0.05).pow(0.53))
        val h = hc + 4.7
        val to = (4.7 * env.mrtC + hc * env.airC) / h
        val rcl = 0.155 * clo
        val fcl = 1.0 + 0.15 * clo
        val he = 16.5 * hc
        val reCl = rcl / (0.45 * 0.0165) / 1000.0
        val qDry = (tsk - to) / (rcl + 1.0 / (fcl * h))
        val qRes = 0.0014 * m * (34.0 - env.airC) + 0.0173 * m * (5.87 - pa)
        val eReq = max(0.0, m - qRes - qDry)
        val eMax = max(1e-6, (Gagge.psatKpa(tsk) - pa) / (reCl + 1.0 / (fcl * he)))
        val w = min(1.0, eReq / eMax)
        val efficiency = max(0.3, 1.0 - w * w / 2.0)
        val sReq = eReq / efficiency
        val sMax = if (acclimatised) 300.0 else 250.0 // W/m^2
        val sw = min(sReq, sMax)
        return Result(sw / 0.675 * bsa, sReq > sMax)
    }
}

/**
 * Heat-balance prior for the ECTemp prediction step (#24). Each minute it starts the Gagge core node at the filter's
 * current estimate and returns the one-minute increment. Fail-safe asymmetries: without environment input the increment
 * is exactly 0 (never a guessed cooling), and cooling is clamped to a small fraction of the warming limit.
 */
class HeatBalancePrior(private val body: Anthropometrics, private val clo: Double = 0.5, var enabled: Boolean = true) {
    private var skin = 34.0
    var lastSweatGPerM2h: Double = 0.0; private set

    fun incrementC(coreC: Double, env: ThermalEnv?, metW: Double?): Double {
        if (!enabled || env == null || metW == null) return 0.0
        val s = Gagge.step(GaggeState(coreC, skin), env, metW, clo, body, 60.0)
        skin = s.state.tsk
        lastSweatGPerM2h = s.sweatGPerM2h
        return (s.state.tcr - coreC).coerceIn(-MAX_COOLING_PER_MIN, MAX_WARMING_PER_MIN)
    }

    companion object {
        const val MAX_WARMING_PER_MIN = 0.05
        const val MAX_COOLING_PER_MIN = 0.002
    }
}


