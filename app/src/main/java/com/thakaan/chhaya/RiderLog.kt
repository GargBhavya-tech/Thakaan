package com.thakaan.chhaya

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * What this phone itself has seen on the streets it has been along (display only, never used for routing): the phone signal
 * level and how fast a rider actually moved compared with the road's free-flow speed. Everything is kept per street segment as
 * a sum and a count, on the phone, and shown only after [MIN_SAMPLES] samples so one noisy GPS point cannot colour a street.
 * Nothing here leaves the phone, and replayed or simulated readings are never added (the caller only passes REAL ones).
 */
class RiderLog {
    companion object {
        const val MIN_SAMPLES = 3

        fun fromJson(s: String): RiderLog = try {
            val o = JSONObject(s)
            val log = RiderLog()
            fun read(key: String, into: HashMap<Int, DoubleArray>) {
                val t = o.optJSONObject(key) ?: return
                for (k in t.keys()) { val a = t.getJSONArray(k); into[k.toInt()] = doubleArrayOf(a.getDouble(0), a.getDouble(1)) }
            }
            read("sig", log.signal); read("spd", log.speed)
            log
        } catch (_: Exception) { RiderLog() }
    }

    private val signal = HashMap<Int, DoubleArray>()
    private val speed = HashMap<Int, DoubleArray>()

    /** [level] 0 (no service) to 4 (full bars). */
    @Synchronized fun addSignal(edge: Int, level: Int) { val a = signal.getOrPut(edge) { DoubleArray(2) }; a[0] += level.coerceIn(0, 4); a[1] += 1.0 }

    /** [ratio] is the rider's speed divided by the road's free-flow speed; a value above 1 is capped. */
    @Synchronized fun addSpeed(edge: Int, ratio: Double) { val a = speed.getOrPut(edge) { DoubleArray(2) }; a[0] += ratio.coerceIn(0.0, 1.0); a[1] += 1.0 }

    /** Mean signal level 0..4 on this street, or null when there are fewer than [MIN_SAMPLES] samples. */
    @Synchronized fun signalMean(edge: Int): Double? = signal[edge]?.takeIf { it[1] >= MIN_SAMPLES }?.let { it[0] / it[1] }

    /** Mean speed as a share of free flow (1 = no slowdown), or null when there are too few samples. */
    @Synchronized fun speedRatio(edge: Int): Double? = speed[edge]?.takeIf { it[1] >= MIN_SAMPLES }?.let { it[0] / it[1] }

    @Synchronized fun signalEdges(): Set<Int> = signal.filterValues { it[1] >= MIN_SAMPLES }.keys.toSet()
    @Synchronized fun speedEdges(): Set<Int> = speed.filterValues { it[1] >= MIN_SAMPLES }.keys.toSet()

    @Synchronized fun toJson(): String {
        fun write(m: Map<Int, DoubleArray>) = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k.toString(), org.json.JSONArray().put(v[0]).put(v[1])) } }
        return JSONObject().put("sig", write(signal)).put("spd", write(speed)).toString()
    }
}

/** How the phone signal reads on the map. Level 4 is full bars. */
enum class SignalBand(val word: String) {
    GOOD("Good signal"), WEAK("Weak signal"), POOR("Poor or none");

    companion object {
        fun of(meanLevel: Double): SignalBand = if (meanLevel >= 2.5) GOOD else if (meanLevel >= 1.5) WEAK else POOR
    }
}

/**
 * Typical traffic slowdown by road class and hour. This is an ASSUMED pattern (busier main roads at office hours and early
 * evening, quiet side streets), not a measurement and not live traffic, and the screen says so. Road class comes from the free-flow
 * speed the graph stores per street (main roads 28 km/h and up, collectors 22 to 25, side streets below).
 * Rider-observed speeds, where the phone has enough, are shown instead of this estimate.
 */
object TrafficEstimate {
    enum class Band(val word: String) { FREE("Usually free"), SLOW("Usually slow"), JAMMED("Usually jammed") }

    /** Share of free-flow speed typically lost, 0..1. [dayOfWeek] uses java.util.Calendar numbering (1 = Sunday). */
    fun slowdown(freeKmh: Double, dayOfWeek: Int, minuteOfDay: Int): Double {
        val weekend = dayOfWeek == 1 || dayOfWeek == 7
        val h = minuteOfDay / 60.0
        val peak = !weekend && ((h >= 8 && h < 11) || (h >= 17 && h < 21))
        val shoulder = !peak && ((h >= 7 && h < 8) || (h >= 11 && h < 12) || (h >= 16 && h < 17) || (h >= 21 && h < 22) || (weekend && h >= 10 && h < 21))
        val (p, s, o) = when {
            freeKmh >= 28 -> Triple(0.50, 0.25, 0.10)
            freeKmh >= 22 -> Triple(0.30, 0.15, 0.05)
            else -> Triple(0.10, 0.05, 0.0)
        }
        return if (peak) p else if (shoulder) s else o
    }

    fun band(slowdownShare: Double): Band = if (slowdownShare >= 0.4) Band.JAMMED else if (slowdownShare >= 0.2) Band.SLOW else Band.FREE

    /** A rider-measured speed ratio (1 = free flow) mapped to the same bands. */
    fun bandOfRatio(ratio: Double): Band = band(1.0 - ratio)
}

/** India's CPCB PM2.5 bands (micrograms per cubic metre). They are defined for 24-hour averages; the hourly modelled value here is only placed on the same scale. */
enum class PmBand(val word: String, val upTo: Double) {
    GOOD("Good", 30.0), SATISFACTORY("Satisfactory", 60.0), MODERATE("Moderate", 90.0),
    POOR("Poor", 120.0), VERY_POOR("Very poor", 250.0), SEVERE("Severe", Double.MAX_VALUE);

    companion object { fun of(pm25: Double): PmBand = entries.first { pm25 <= it.upTo } }
}

/** One shared copy of the road graph for the map screen and the background rider feed. */
object RoadGraphStore {
    @Volatile private var g: RoadGraph? = null
    @Synchronized fun get(ctx: android.content.Context): RoadGraph? = g ?: try {
        ctx.assets.open("chhaya/graph.json").bufferedReader().use { RoadGraph(it.readText()) }.also { g = it }
    } catch (_: Exception) { null }
}

object RiderLogStore {
    private var instance: RiderLog? = null
    private const val PREFS = "thakaan_riderlog"

    @Synchronized fun get(ctx: android.content.Context): RiderLog = instance ?: RiderLog.fromJson(
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString("log", "{}") ?: "{}",
    ).also { instance = it }

    fun save(ctx: android.content.Context) {
        val l = instance ?: return
        ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString("log", l.toJson()).apply()
    }
}

/**
 * Turns the running shift's position, speed and the phone's signal into [RiderLog] samples, off the calling thread. One sample
 * every [EVERY_MS]; only REAL readings, only a position that snaps to a mapped street within 25 m, and speed only while riding
 * and moving (a rider stopped at a tea stall is not traffic).
 */
object RiderMapFeed {
    const val EVERY_MS = 5_000L
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var last = 0L
    @Volatile private var lastSave = 0L
    private val scope = CoroutineScope(Dispatchers.Default)

    fun offer(ctx: android.content.Context, lat: Double, lon: Double, speedMs: Double?, riding: Boolean, signalLevel: Int?, real: Boolean) {
        if (!real) return
        val now = System.currentTimeMillis()
        if (now - last < EVERY_MS || !busy.compareAndSet(false, true)) return
        last = now
        scope.launch {
            try {
                val g = RoadGraphStore.get(ctx) ?: return@launch
                val e = g.nearestEdge(lat, lon, 25.0) ?: return@launch
                val log = RiderLogStore.get(ctx)
                if (signalLevel != null) log.addSignal(e, signalLevel)
                if (riding && speedMs != null && speedMs > 0.5) log.addSpeed(e, speedMs / (g.edgeSpeedKmh[e] / 3.6))
                if (now - lastSave > 60_000L) { lastSave = now; RiderLogStore.save(ctx) }
            } finally { busy.set(false) }
        }
    }
}
