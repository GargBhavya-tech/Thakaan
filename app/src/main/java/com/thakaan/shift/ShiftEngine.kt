package com.thakaan.shift

import com.thakaan.body.AdaptiveEcTemp
import com.thakaan.body.Anthropometrics
import com.thakaan.body.EcTempFilter
import com.thakaan.body.HeatBalancePrior
import com.thakaan.body.HydrationLedger
import com.thakaan.body.ShiftStart
import com.thakaan.body.ThermalEnv
import com.thakaan.brain.BrainBattery
import com.thakaan.core.Provenance
import com.thakaan.exposure.Irradiance
import com.thakaan.exposure.LightInput
import com.thakaan.exposure.LightTracker
import com.thakaan.exposure.RestLedger
import com.thakaan.exposure.SolarPosition
import com.thakaan.exposure.SunDose
import com.thakaan.exposure.SunState
import com.thakaan.exposure.Wbgt
import com.thakaan.exposure.WbgtInput
import com.thakaan.exposure.WeatherCache
import com.thakaan.motion.Activity
import com.thakaan.motion.ActivityEngine
import com.thakaan.motion.CollapseDetector
import com.thakaan.motion.CountdownMachine
import com.thakaan.motion.CountdownState
import com.thakaan.motion.Metabolism
import com.thakaan.motion.MoveState
import com.thakaan.motion.StateStamp
import com.thakaan.motion.StopDetector
import com.thakaan.risk.BodyInputs
import com.thakaan.risk.BrainInputs
import com.thakaan.risk.ExposureInputs
import com.thakaan.risk.Guards
import com.thakaan.risk.Level
import com.thakaan.risk.RiskEngine
import com.thakaan.risk.RiskInputs
import com.thakaan.risk.StrainForecast
import com.thakaan.risk.Symptom
import com.thakaan.risk.TssSample
import com.thakaan.brain.SymptomLog
import com.thakaan.sensorhub.Reading
import com.thakaan.sensorhub.Stream
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

class ShiftConfig(
    /** Epoch seconds that the first reading's timestamp corresponds to (live: now; replay: the recording's start). */
    val startEpochSec: Long,
    val bodyMassKg: Double = 70.0,
    val heightM: Double = 1.70,
    val ageYears: Int = 30,
    val acclimatisationDays: Int = 0,
    val restSeatedHr: Double = 70.0,
    val clo: Double = 0.5,
    val defaultLat: Double = 12.9716,
    val defaultLon: Double = 77.5946,
    val utcOffsetSec: Int = 19800,
    val heatPrior: Boolean = true,
    val guards: Guards = Guards(),
)

class ShiftSnapshot(
    val tNanos: Long,
    val level: Level,
    val tss: Int, val tssLow: Int, val tssHigh: Int,
    val drivers: List<String>,
    val anchors: List<String>,
    val changedFrom: Level?,
    val provenance: Provenance,
    val activity: Activity,
    val moveState: MoveState,
    val sunState: SunState,
    val sunDoseMin: Double,
    val wbgtC: Double?,
    val minutesSinceRest: Double,
    val restDebtMin: Double,
    val hydrationText: String,
    val forecastHeadline: String?,
    val collapseSecondsLeft: Int,
    val collapseAlarm: Boolean,
)

/**
 * The spine that turns sensor readings into one level (glue for tickets 5, 13 to 21, 24, 25, 32 to 42). Everything it
 * does is deterministic given its inputs, so a REPLAY of a recording gives the same level changes at the same points.
 * Provenance: the snapshot's provenance is the combination (never better than the worst) of every reading it used.
 */
class ShiftEngine(
    private val cfg: ShiftConfig,
    private val weather: WeatherCache?,
    private val wbgt: Wbgt = Wbgt(),
    val battery: BrainBattery = BrainBattery(),
) {
    private val activityEngine = ActivityEngine()
    private val stop = StopDetector()
    private val light = LightTracker()
    private val dose = SunDose()
    private val rest = RestLedger()
    private val collapse = CollapseDetector()
    val countdown = CountdownMachine()
    val risk = RiskEngine(cfg.guards)
    val symptoms = SymptomLog()
    private val body = Anthropometrics(cfg.bodyMassKg, cfg.heightM)
    val hydration = HydrationLedger(cfg.bodyMassKg)
    private val prior = HeatBalancePrior(body, cfg.clo, cfg.heatPrior)
    private val start = ShiftStart.of(((cfg.startEpochSec + cfg.utcOffsetSec) % 86400) / 3600.0, null, null)
    private val ec = EcTempFilter(start = start)
    private val adaptive = AdaptiveEcTemp(start = start)
    private val forecastHistory = ArrayList<TssSample>()

    private var t0: Long? = null
    private var lux: Double? = null; private var luxProv = Provenance.REAL
    private var cct: Double? = null
    private var lat: Double = cfg.defaultLat; private var lon: Double = cfg.defaultLon
    private var speed: Double? = null; private var gnssT = 0L; private var gnssProv = Provenance.REAL
    private var gx = 0.0; private var gy = 0.0; private var gz = 9.81
    private var lastTickNanos: Long? = null
    private var minuteIndex: Long? = null
    private var pendingHr: Double? = null
    private var hrAccepted = false

    private var lastHr: Double? = null
    private var lastHrNanos: Long? = null
    private var lastHrQuality = 0.0
    private var lastHrProv = Provenance.REAL
    private var restHr: Double? = null
    private var restHighSince: Long? = null
    private var recoveryPassed = false
    private var env: ThermalEnv? = null
    private var metW: Double? = null
    private var lastWbgt: Double? = null
    private var lastStamp: StateStamp = StateStamp(MoveState.STOPPED, null, null)
    private var lastActivity = Activity.IDLE
    private var lastSun = SunState.UNKNOWN

    init { ec.prior = { ct -> prior.incrementC(ct, env, metW) } }

    private fun epochOf(tNanos: Long): Long = cfg.startEpochSec + (tNanos - (t0 ?: tNanos)) / 1_000_000_000L
    private fun localHour(epoch: Long): Double = (((epoch + cfg.utcOffsetSec) % 86400 + 86400) % 86400) / 3600.0

    // ------------------------------------------------------------------ inputs from outside the sensor hub

    /** An accepted fingertip heart rate (from #11), with the state it was taken in. */
    fun onHeartCheck(tNanos: Long, hrBpm: Double, quality: Double, stamp: StateStamp, provenance: Provenance = Provenance.REAL) {
        lastHr = hrBpm; lastHrNanos = tNanos; lastHrQuality = quality * stamp.hrWeight(); lastHrProv = provenance
        pendingHr = hrBpm; hrAccepted = true
        val resting = stamp.state == MoveState.RESTING || (stamp.state == MoveState.STOPPED && (stamp.secSinceStop ?: 0) >= 60)
        if (resting && stamp.hrWeight() >= 1.0) {
            restHr = hrBpm
            if (hrBpm > 90.0) { if (restHighSince == null) restHighSince = tNanos } else restHighSince = null
        }
    }

    fun onDrink(ml: Double) = hydration.logDrink(ml)
    fun onSymptoms(tNanos: Long, set: Set<Symptom>, effort: Int?, trigger: String) = symptoms.record(tNanos, set, effort, trigger)
    fun onRecoveryPassed() { recoveryPassed = true }
    fun confirmSafe() = countdown.confirmSafe()

    // ------------------------------------------------------------------ sensor stream

    /** Feed every hub reading. Returns a snapshot about once a second, otherwise null. */
    fun onReading(r: Reading): ShiftSnapshot? {
        if (t0 == null) t0 = r.tNanos
        when (r.stream) {
            Stream.LIGHT -> { lux = r.values[0].toDouble(); luxProv = r.provenance }
            Stream.CCT -> cct = r.values[0].toDouble()
            Stream.GNSS -> {
                lat = r.values[0].toDouble(); lon = r.values[1].toDouble()
                if (r.values.size > 3) speed = r.values[3].toDouble()
                gnssT = r.tNanos; gnssProv = r.provenance
            }
            Stream.ACCEL -> {
                gx = 0.98 * gx + 0.02 * r.values[0]; gy = 0.98 * gy + 0.02 * r.values[1]; gz = 0.98 * gz + 0.02 * r.values[2]
                collapse.onAccel(r.tNanos, r.values[0].toDouble(), r.values[1].toDouble(), r.values[2].toDouble())?.let { countdown.start(r.tNanos / 1_000_000L, it.kind) }
            }
            else -> {}
        }
        val est = activityEngine.onReading(r) ?: return null
        return tick(est.tNanos, est.activity, est.mets, est.speedMs, est.provenance, est)
    }

    private fun tick(tNanos: Long, activity: Activity, mets: Double, speedMs: Double?, actProv: Provenance, est: com.thakaan.motion.ActivityEstimate): ShiftSnapshot {
        val dt = lastTickNanos?.let { ((tNanos - it) / 1e9).coerceIn(0.0, 5.0) } ?: 0.0
        lastTickNanos = tNanos
        val epoch = epochOf(tNanos)
        val stamp = stop.update(est)
        lastStamp = stamp; lastActivity = activity
        collapse.setRiding(activity == Activity.RIDING)
        val nowMs = tNanos / 1_000_000L
        val alarmNow = countdown.tick(nowMs)

        val w = weather?.at(epoch)
        val sun = SolarPosition.at(epoch, lat, lon)
        val gps = ((tNanos - gnssT) / 1e9 < 30.0)
        val input = LightInput(
            tNanos, epoch, lat, lon, lux, cct, doubleArrayOf(gx, gy, gz), null, speedMs, w?.cloudPct, null,
            Provenance.combine(luxProv, actProv),
        )
        val le = light.update(input)
        lastSun = le.state
        val irr = Irradiance.estimate(lux, sun, le, w?.cloudPct, Provenance.combine(le.provenance, actProv))
        dose.update(dt, irr.wm2, le.effectiveSun, sun, irr.provenance)
        rest.update(tNanos, stamp, le)

        // WBGT: Liljegren only; null (native lib or weather missing) is treated as hot by the risk engine
        lastWbgt = if (w == null) null else {
            val solar = if (le.effectiveSun) max(w.ghiWm2, irr.wm2) else 0.2 * w.ghiWm2
            val apparentWind = max(0.5, sqrt(w.windMs * w.windMs + (speedMs ?: 0.0) * (speedMs ?: 0.0)))
            wbgt.compute(WbgtInput(epoch * 1000, lat, lon, solar, w.pressureHpa, w.tempC, w.relativeHumidityPct, apparentWind))?.wbgtC
        }
        env = w?.let { ww ->
            val mrt = ThermalEnv.mrtFromIrradiance(ww.tempC, max(ww.ghiWm2, irr.wm2), if (le.effectiveSun) 1.0 else 0.15)
            ThermalEnv(ww.tempC, mrt, ww.relativeHumidityPct, max(0.3, hypot(ww.windMs, speedMs ?: 0.0)), ww.pressureHpa / 10.0)
        }
        metW = Metabolism.watts(activity, cfg.bodyMassKg, speedMs)

        // the body filter and ledgers advance once per minute
        val idx = epoch / 60
        if (minuteIndex == null || idx > minuteIndex!!) {
            val hr = pendingHr; pendingHr = null
            ec.update(idx * 60, hr)
            adaptive.update(idx * 60, hr)
            hydration.addMinute(prior.lastSweatGPerM2h, body.bsa, metW ?: 100.0)
            minuteIndex = idx
        }

        val core = if (hrAccepted) max(ec.ct, adaptive.ctForAnchors) else null
        val hrAge = lastHrNanos?.let { (tNanos - it) / 1e9 }
        val psi = if (core != null && lastHr != null) (5.0 * (core - 37.0) / 2.5 + 5.0 * (lastHr!! - 60.0) / 120.0).coerceIn(0.0, 10.0) else null
        val restHighMin = restHighSince?.let { (tNanos - it) / 60e9 } ?: 0.0
        val band = hydration.band()
        val bodyIn = BodyInputs(
            hrAgeSec = hrAge, hrQuality = lastHrQuality, restHrBpm = restHr, restHrSustainedMin = restHighMin,
            coreTrendC = core, psi = psi, hydrationDeficitPct = band.midPct,
        )
        val sunNow: Boolean? = if (le.state == SunState.UNKNOWN) null else le.effectiveSun
        val exposure = ExposureInputs(lastWbgt, dose.weightedMinutes, rest.restDebtMinutes(tNanos), localHour(epoch), cfg.acclimatisationDays, sunNow)
        val prov = Provenance.combine(actProv, le.provenance, irr.provenance, w?.provenance ?: Provenance.REAL, lastHrProv, dose.provenance, if (gps) gnssProv else Provenance.REAL)
        val out = risk.update(
            RiskInputs(
                tNanos, exposure, bodyIn, BrainInputs(battery.flaggedFamilies()), symptoms.active(tNanos),
                collapse = countdown.state == CountdownState.ALARM, recoveryPassed = recoveryPassed, provenance = prov,
            ),
        )
        recoveryPassed = false

        if (forecastHistory.isEmpty() || tNanos / 60_000_000_000.0 - forecastHistory.last().tMin >= 1.0) {
            forecastHistory += TssSample(tNanos / 60_000_000_000.0, out.tss)
            if (forecastHistory.size > 60) forecastHistory.removeAt(0)
        }
        val headline = StrainForecast.project(forecastHistory, if (le.effectiveSun) 1.0 else 0.3)?.headline

        return ShiftSnapshot(
            tNanos, out.level, out.tss, out.tssLow, out.tssHigh, out.drivers.map { it.text }, out.anchors, out.changedFrom, out.provenance,
            activity, stamp.state, le.state, dose.minutesInSun, lastWbgt, rest.minutesSinceRest(tNanos), rest.restDebtMinutes(tNanos),
            band.text, headline, countdown.secondsLeft(nowMs), alarmNow || countdown.state == CountdownState.ALARM,
        )
    }
}

