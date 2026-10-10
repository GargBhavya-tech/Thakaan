package com.thakaan.brain

import com.thakaan.risk.BrainFamily
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class BrainTest(val family: BrainFamily, val higherIsWorse: Boolean) {
    TREMOR(BrainFamily.MOTION, true),
    GAIT_STRIDE_CV(BrainFamily.MOTION, true),
    GAIT_SWAY(BrainFamily.MOTION, true),
    FITTS_THROUGHPUT(BrainFamily.TOUCH, false),
    FITTS_ERRORS(BrainFamily.TOUCH, true),
    PVT_SPEED(BrainFamily.TOUCH, false),
    SPEECH_RATE(BrainFamily.VOICE, false),
    SPEECH_WRONG(BrainFamily.VOICE, true),
    RIDING_ENTROPY(BrainFamily.NAVIGATION, true),
    FACE_PERCLOS(BrainFamily.FACE, true),
    FACE_YAWN_RATE(BrainFamily.FACE, true),
}

/** One stored result. Scores are only ever compared with the same worker, same device, same placement, same time of day. */
class BrainResult(
    val test: BrainTest,
    val value: Double,
    val tNanos: Long,
    val hourOfDay: Double,
    val deviceId: String,
    /** Phone placement (pocket / hand / mounted ...) for tests where it changes the signal; empty otherwise. */
    val placement: String = "",
    val flags: Set<String> = emptySet(),
)

object Robust {
    fun median(x: List<Double>): Double {
        require(x.isNotEmpty())
        val s = x.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else 0.5 * (s[n / 2 - 1] + s[n / 2])
    }

    /** Median absolute deviation scaled to be comparable with a standard deviation. */
    fun sigma(x: List<Double>, floor: Double): Double {
        val m = median(x)
        return max(1.4826 * median(x.map { abs(it - m) }), floor)
    }
}

/** Answers collected at the check; null = not asked or not answered, which never counts as "fine". */
class BrainContext(
    val sleepPoorLastNight: Boolean? = null,
    val caffeineRecently: Boolean? = null,
    /** 0 to 1 from WBGT / sun dose. */
    val exposureLoad: Double = 1.0,
)

/**
 * Escalation weight for one test result. Fail-safe: the prior can only DOWN-weight when we positively know a benign
 * cause (poor sleep, caffeine) AND exposure is low; any heavy exposure keeps the weight at 1 or above, and unknown
 * answers are neutral.
 */
object ContextPrior {
    fun weight(test: BrainTest, c: BrainContext): Double {
        var w = 1.0
        val lowExposure = c.exposureLoad < 0.35
        if (lowExposure && c.sleepPoorLastNight == true) w *= 0.5      // a tired cool morning is not heat strain
        if (lowExposure && c.caffeineRecently == true && test == BrainTest.TREMOR) w *= 0.6
        if (c.exposureLoad >= 0.7) w *= 1.25                           // the same slowing in heavy heat means more
        return w
    }
}

/** Upper CUSUM on a z-score where larger = worse. Alarm clears only through enough normal results, never by silence. */
class CusumTracker(val k: Double = 0.5, val h: Double = 4.5, private val zCap: Double = 3.5) {
    var s: Double = 0.0; private set
    var alarmed: Boolean = false; private set

    fun update(z: Double, weight: Double = 1.0): Boolean {
        val zc = min(z, zCap) // one wild result must not alarm by itself
        s = max(0.0, s + weight * (zc - k))
        if (s >= h) alarmed = true
        else if (alarmed && s <= h / 2) alarmed = false // hysteresis
        return alarmed
    }

    fun reset() { s = 0.0; alarmed = false }
}

/** Cool-baseline samples for one (test, device, placement, time-of-day bucket). */
class BaselineStore(private val minSamples: Int = 5, private val bucketHours: Int = 3) {
    private val samples = HashMap<String, MutableList<Double>>()

    private fun key(t: BrainTest, device: String, placement: String, bucket: Int?) = "$t|$device|$placement|${bucket ?: "all"}"
    private fun bucketOf(hour: Double) = (hour.toInt().coerceIn(0, 23)) / bucketHours

    fun add(r: BrainResult) {
        samples.getOrPut(key(r.test, r.deviceId, r.placement, bucketOf(r.hourOfDay))) { ArrayList() }.add(r.value)
        samples.getOrPut(key(r.test, r.deviceId, r.placement, null)) { ArrayList() }.add(r.value)
    }

    class Z(val z: Double, val sameTimeOfDay: Boolean)

    /** Signed so that positive = worse. Null when we do not yet have enough baseline for this exact device and placement. */
    fun zFor(r: BrainResult, sigmaFloor: Double): Z? {
        fun from(list: List<Double>?, same: Boolean): Z? {
            if (list == null || list.size < minSamples) return null
            val m = Robust.median(list)
            val sd = Robust.sigma(list, sigmaFloor)
            val raw = (r.value - m) / sd
            return Z(if (r.test.higherIsWorse) raw else -raw, same)
        }
        return from(samples[key(r.test, r.deviceId, r.placement, bucketOf(r.hourOfDay))], true)
            ?: from(samples[key(r.test, r.deviceId, r.placement, null)], false)
    }

    fun count(t: BrainTest, device: String, placement: String): Int = samples[key(t, device, placement, null)]?.size ?: 0
}

/**
 * Brain battery fusion (#36): baseline -> robust z -> context-weighted CUSUM per (test, device, placement).
 * Output is the set of flagged families that goes to the risk engine; two flagged families force at least Strained there.
 */
class BrainBattery(
    private val baselines: BaselineStore = BaselineStore(),
    private val sigmaFloors: Map<BrainTest, Double> = DEFAULT_FLOORS,
) {
    private val trackers = HashMap<String, CusumTracker>()
    private val flagged = HashMap<String, BrainTest>()
    private val confounded = HashSet<BrainTest>()

    private fun tkey(r: BrainResult) = "${r.test}|${r.deviceId}|${r.placement}"

    /** A cool, well-rested session result: builds the baseline and nothing else. */
    fun addBaseline(r: BrainResult) = baselines.add(r)

    /** Returns true if this result moved the alarm state. */
    fun ingest(r: BrainResult, ctx: BrainContext): Boolean {
        if ("lifting_confound" in r.flags || "unreliable" in r.flags) { confounded += r.test; return false } // flagged, not scored
        val z = baselines.zFor(r, sigmaFloors[r.test] ?: 0.01) ?: return false
        val tr = trackers.getOrPut(tkey(r)) { CusumTracker() }
        val before = tr.alarmed
        // a baseline from a different time of day is a weaker comparison, so it earns a smaller step
        val w = ContextPrior.weight(r.test, ctx) * (if (z.sameTimeOfDay) 1.0 else 0.7)
        val after = tr.update(z.z, w)
        if (after) flagged[tkey(r)] = r.test else flagged.remove(tkey(r))
        return before != after
    }

    fun flaggedTests(): Set<BrainTest> = flagged.values.toSet()
    fun flaggedFamilies(): Set<BrainFamily> = flagged.values.map { it.family }.toSet()
    fun skippedForConfound(): Set<BrainTest> = confounded

    fun cusumState(test: BrainTest, device: String, placement: String = ""): Double = trackers["$test|$device|$placement"]?.s ?: 0.0

    companion object {
        /** Smallest believable spread per test, so a very quiet baseline cannot turn noise into alarms. */
        val DEFAULT_FLOORS: Map<BrainTest, Double> = mapOf(
            BrainTest.TREMOR to 0.0005, BrainTest.GAIT_STRIDE_CV to 0.01, BrainTest.GAIT_SWAY to 0.05,
            BrainTest.FITTS_THROUGHPUT to 0.15, BrainTest.FITTS_ERRORS to 0.02, BrainTest.PVT_SPEED to 0.15,
            BrainTest.SPEECH_RATE to 0.2, BrainTest.SPEECH_WRONG to 0.1, BrainTest.RIDING_ENTROPY to 0.05,
            BrainTest.FACE_PERCLOS to 0.02, BrainTest.FACE_YAWN_RATE to 0.3,
        )
    }
}

internal fun rms(x: DoubleArray): Double = if (x.isEmpty()) 0.0 else sqrt(x.sumOf { it * it } / x.size)
