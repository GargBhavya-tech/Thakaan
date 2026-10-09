package com.thakaan.motion

import com.thakaan.core.Provenance
import com.thakaan.ppg.Dsp
import com.thakaan.sensorhub.Reading
import com.thakaan.sensorhub.Stream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sqrt

enum class Activity { IDLE, RIDING, WALKING, STAIRS, LIFTING }

enum class Placement { MOUNTED, POCKET, IN_HAND, DESK, UNKNOWN }

/** One window of motion summarised. Computed on 2 s of accelerometer (and gyro magnitude) data. */
class MotionFeatures(
    /** Standard deviation of |a| with gravity removed (m/s^2). */
    val dynStd: Double,
    /** Strongest rhythm between 0.5 and 5 Hz (steps, pedalling), in Hz. */
    val cadenceHz: Double,
    /** Share of the signal's power that sits in that one rhythm (0 to 1). Walking is rhythmic, road vibration is not. */
    val cadenceShare: Double,
    /** Spread of acceleration along the gravity direction (m/s^2). Stairs are bouncier than level walking. */
    val verticalStd: Double,
    /** Share of the power above 8 Hz: engine and road vibration. */
    val hfShare: Double,
    val gyroRms: Double,
    /** Angle between the screen plane and the horizontal: 0 = flat on a table, 90 = upright. */
    val tiltFromHorizontalDeg: Double,
    val tiltJitterDeg: Double,
    val peakDyn: Double,
)

object MotionFeaturesExtractor {
    fun compute(ax: DoubleArray, ay: DoubleArray, az: DoubleArray, gyroMag: DoubleArray?, fs: Double): MotionFeatures {
        val n = ax.size
        val mag = DoubleArray(n) { sqrt(ax[it] * ax[it] + ay[it] * ay[it] + az[it] * az[it]) }
        val gMean = Dsp.mean(mag)
        val dyn = DoubleArray(n) { mag[it] - gMean }
        val dynStd = Dsp.std(dyn)

        val mx = Dsp.mean(ax); val my = Dsp.mean(ay); val mz = Dsp.mean(az)
        val gl = sqrt(mx * mx + my * my + mz * mz).coerceAtLeast(1e-9)
        val ux = mx / gl; val uy = my / gl; val uz = mz / gl
        val vertical = DoubleArray(n) { ax[it] * ux + ay[it] * uy + az[it] * uz }
        val verticalStd = Dsp.std(vertical)

        // rhythm: fine grid 0.5..5 Hz, coarse grid above
        val lowF = DoubleArray(46) { 0.5 + 0.1 * it }
        val highTop = minOf(40.0, fs * 0.45)
        val highF = if (highTop > 5.5) DoubleArray(((highTop - 5.5) / 0.5).toInt() + 1) { 5.5 + 0.5 * it } else DoubleArray(0)
        val pl = Dsp.powerAt(dyn, fs, lowF)
        val ph = if (highF.isNotEmpty()) Dsp.powerAt(dyn, fs, highF) else DoubleArray(0)
        val totalLow = pl.sum() * 0.1
        val totalHigh = ph.sum() * 0.5
        val total = (totalLow + totalHigh).coerceAtLeast(1e-18)
        val peak = pl.indices.maxByOrNull { pl[it] } ?: 0
        val share = pl.indices.filter { abs(lowF[it] - lowF[peak]) <= 0.3 }.sumOf { pl[it] } * 0.1
        val hf = highF.indices.filter { highF[it] >= 8.0 }.sumOf { ph[it] } * 0.5

        val gyroRms = if (gyroMag == null || gyroMag.isEmpty()) 0.0 else sqrt(gyroMag.sumOf { it * it } / gyroMag.size)

        val tiltDeg = acos(abs(uz).coerceIn(0.0, 1.0)) * 180.0 / PI
        // jitter: tilt of 0.25 s blocks
        val block = max(1, (0.25 * fs).toInt())
        val tilts = ArrayList<Double>()
        var i = 0
        while (i + block <= n) {
            val bx = Dsp.mean(ax.copyOfRange(i, i + block)); val by = Dsp.mean(ay.copyOfRange(i, i + block)); val bz = Dsp.mean(az.copyOfRange(i, i + block))
            val bl = sqrt(bx * bx + by * by + bz * bz).coerceAtLeast(1e-9)
            tilts += acos(abs(bz / bl).coerceIn(0.0, 1.0)) * 180.0 / PI
            i += block
        }
        return MotionFeatures(
            dynStd = dynStd, cadenceHz = lowF[peak], cadenceShare = share / total, verticalStd = verticalStd,
            hfShare = hf / total, gyroRms = gyroRms, tiltFromHorizontalDeg = tiltDeg,
            tiltJitterDeg = Dsp.std(tilts.toDoubleArray()), peakDyn = dyn.maxOfOrNull { abs(it) } ?: 0.0,
        )
    }
}

/**
 * Thresholds for the rules-first classifier. These are starting values for a phone in the usual places and MUST be
 * tuned with recordings (ticket #7): ride, walk, stairs, sit. A 1D-CNN on the NPU can replace the rules later.
 */
data class ActivityRules(
    val idleDynStd: Double = 0.12,
    val ridingSpeedMs: Double = 3.0,
    val walkCadenceMin: Double = 1.0,
    val walkCadenceMax: Double = 2.8,
    val walkCadenceShare: Double = 0.30,
    val walkMinDynStd: Double = 0.6,
    val stairsVerticalStd: Double = 2.6,
    val stairsMaxSpeedMs: Double = 1.0,
    val liftGyroRms: Double = 1.0,
    val liftPeakDyn: Double = 8.0,
    val vibrationHfShare: Double = 0.25,
)

class ActivityDecision(val activity: Activity, val confidence: Double)

object ActivityClassifier {
    /**
     * Rules first. Fail-safe (invariant 3): when GNSS speed is missing or the window is ambiguous, pick the activity
     * with the HIGHER metabolic rate rather than the lower, so losing information never makes the worker look less loaded.
     */
    fun classify(f: MotionFeatures, speedMs: Double?, r: ActivityRules = ActivityRules()): ActivityDecision {
        val rhythmic = f.cadenceShare >= r.walkCadenceShare && f.cadenceHz in r.walkCadenceMin..r.walkCadenceMax && f.dynStd >= r.walkMinDynStd
        val fastGnss = speedMs != null && speedMs > r.ridingSpeedMs

        if (fastGnss && !rhythmic) return ActivityDecision(Activity.RIDING, 0.9)
        if (f.dynStd < r.idleDynStd) {
            // smooth riding can look still; GNSS decides
            return if (fastGnss) ActivityDecision(Activity.RIDING, 0.7) else ActivityDecision(Activity.IDLE, 0.9)
        }
        // Heavy heaves with strong rotation and no travel: lifting, even if the heaves repeat (walking peaks stay well below this).
        if (f.gyroRms >= r.liftGyroRms && f.peakDyn >= r.liftPeakDyn && (speedMs == null || speedMs < 1.0)) return ActivityDecision(Activity.LIFTING, 0.6)
        if (rhythmic) {
            val slow = speedMs == null || speedMs <= r.stairsMaxSpeedMs
            if (f.verticalStd >= r.stairsVerticalStd && slow) return ActivityDecision(Activity.STAIRS, if (speedMs == null) 0.5 else 0.7)
            return ActivityDecision(Activity.WALKING, 0.8)
        }
        val slowOrUnknown = speedMs == null || speedMs < 1.0
        if (f.gyroRms >= r.liftGyroRms && f.peakDyn >= r.liftPeakDyn && slowOrUnknown) return ActivityDecision(Activity.LIFTING, 0.6)
        if (f.hfShare >= r.vibrationHfShare && (speedMs == null || speedMs > 1.5)) {
            return ActivityDecision(Activity.RIDING, if (speedMs == null) 0.5 else 0.8)
        }
        // moving but not classifiable: assume walking (3.5 MET) rather than idle (1.3 MET)
        return ActivityDecision(Activity.WALKING, 0.3)
    }

    fun placement(f: MotionFeatures, activity: Activity): Placement = when {
        f.tiltFromHorizontalDeg < 12.0 && f.dynStd < 0.06 && f.gyroRms < 0.05 -> Placement.DESK
        activity == Activity.RIDING && f.tiltJitterDeg < 6.0 -> Placement.MOUNTED
        activity == Activity.WALKING || activity == Activity.STAIRS -> if (f.gyroRms >= 0.9) Placement.POCKET else Placement.IN_HAND
        activity == Activity.IDLE && f.tiltFromHorizontalDeg > 50.0 && f.tiltJitterDeg < 1.5 && f.gyroRms < 0.05 -> Placement.MOUNTED
        else -> Placement.UNKNOWN
    }
}

/** Metabolic rate for the heat-balance model (#24) and the excess-HR split (#26). */
object Metabolism {
    private const val WATTS_PER_MET_PER_KG = 1.163

    fun mets(activity: Activity, speedMs: Double? = null): Double = when (activity) {
        Activity.IDLE -> 1.3
        Activity.RIDING -> 2.0 // scooter / motorcycle riding, 1.5 to 2.5
        Activity.WALKING -> {
            val s = (speedMs ?: 1.3).coerceIn(0.5, 2.2) * 60.0 // m/min
            (3.5 + 0.1 * s) / 3.5 // ACSM level walking
        }
        Activity.STAIRS -> 6.0
        Activity.LIFTING -> 5.0
    }

    /** Pandolf et al. (1977) walking-with-load metabolic rate in watts. [terrain] is 1.0 for blacktop, 1.1 for dirt road. */
    fun pandolfWatts(bodyKg: Double, loadKg: Double, speedMs: Double, gradePct: Double = 0.0, terrain: Double = 1.0): Double {
        val w = bodyKg; val l = loadKg; val v = speedMs; val g = gradePct
        return 1.5 * w + 2.0 * (w + l) * (l / w) * (l / w) + terrain * (w + l) * (1.5 * v * v + 0.35 * v * g)
    }

    fun watts(activity: Activity, bodyKg: Double, speedMs: Double? = null, loadKg: Double = 0.0): Double =
        if (activity == Activity.WALKING && loadKg > 0.0) pandolfWatts(bodyKg, loadKg, (speedMs ?: 1.3).coerceIn(0.5, 2.2))
        else mets(activity, speedMs) * WATTS_PER_MET_PER_KG * bodyKg
}

class ActivityEstimate(
    val tNanos: Long,
    val activity: Activity,
    val placement: Placement,
    val mets: Double,
    val speedMs: Double?,
    val confidence: Double,
    val provenance: Provenance,
)

/**
 * Streams hub readings into one [ActivityEstimate] per second, from a rolling 2 s window.
 * GNSS speed older than [gnssStaleSec] is ignored (treated as lost), and lost GNSS only ever biases toward the
 * higher-load activity.
 */
class ActivityEngine(private val rules: ActivityRules = ActivityRules(), private val gnssStaleSec: Double = 5.0) {
    private val windowNanos = 2_000_000_000L
    private val hopNanos = 1_000_000_000L

    private class Sample(val t: Long, val x: Double, val y: Double, val z: Double, val prov: Provenance)

    private val acc = ArrayDeque<Sample>()
    private val gyro = ArrayDeque<Sample>()
    private var speed: Double? = null
    private var speedT = 0L
    private var nextEmit = 0L

    fun onReading(r: Reading): ActivityEstimate? {
        when (r.stream) {
            Stream.ACCEL -> acc.addLast(Sample(r.tNanos, r.values[0].toDouble(), r.values[1].toDouble(), r.values[2].toDouble(), r.provenance))
            Stream.GYRO -> gyro.addLast(Sample(r.tNanos, r.values[0].toDouble(), r.values[1].toDouble(), r.values[2].toDouble(), r.provenance))
            Stream.GNSS -> { if (r.values.size > 3) { speed = r.values[3].toDouble(); speedT = r.tNanos } }
            else -> return null
        }
        if (r.stream != Stream.ACCEL) return null
        while (acc.isNotEmpty() && acc.first().t < r.tNanos - windowNanos) acc.removeFirst()
        while (gyro.isNotEmpty() && gyro.first().t < r.tNanos - windowNanos) gyro.removeFirst()
        if (nextEmit == 0L) nextEmit = acc.first().t + windowNanos
        if (r.tNanos < nextEmit || acc.size < 20) return null
        nextEmit = r.tNanos + hopNanos

        val n = acc.size
        val ax = DoubleArray(n) { acc[it].x }; val ay = DoubleArray(n) { acc[it].y }; val az = DoubleArray(n) { acc[it].z }
        val fs = (n - 1) / ((acc.last().t - acc.first().t) / 1e9)
        val gm = DoubleArray(gyro.size) { sqrt(gyro[it].x * gyro[it].x + gyro[it].y * gyro[it].y + gyro[it].z * gyro[it].z) }
        val f = MotionFeaturesExtractor.compute(ax, ay, az, gm, fs)

        val freshSpeed = speed?.takeIf { (r.tNanos - speedT) / 1e9 <= gnssStaleSec }
        val d = ActivityClassifier.classify(f, freshSpeed, rules)
        val prov = Provenance.combine(*acc.map { it.prov }.distinct().toTypedArray())
        return ActivityEstimate(r.tNanos, d.activity, ActivityClassifier.placement(f, d.activity), Metabolism.mets(d.activity, freshSpeed), freshSpeed, d.confidence, prov)
    }
}
