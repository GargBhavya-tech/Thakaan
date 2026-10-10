package com.thakaan.chhaya

import org.json.JSONArray
import org.json.JSONObject

/**
 * Is this a photo taken just now of something real? The check runs on numbers from the camera frame, never on the picture
 * itself, and the frame is dropped straight after (invariant 7: camera frames stay in memory). A frame counts only if it was
 * captured within [MAX_AGE_MS] of the report tap, is not a covered lens (too dark) and not a flat screen or wall (no detail).
 * This stops a stale or blank report from closing a road. It cannot prove the road is flooded, so reports are shown as
 * "reported flooded", never as a depth or as confirmed.
 */
object FloodPhotoCheck {
    const val MAX_AGE_MS = 5_000L
    const val MIN_MEAN_LUMA = 15.0
    const val MIN_LUMA_STD = 6.0

    /** Why the photo is rejected, or null when it passes. */
    fun problem(ageMs: Long, meanLuma: Double, lumaStd: Double): String? = when {
        ageMs > MAX_AGE_MS || ageMs < 0 -> "Take the photo now, not an old one"
        meanLuma < MIN_MEAN_LUMA -> "The picture is too dark: uncover the camera"
        lumaStd < MIN_LUMA_STD -> "The picture has no detail: point it at the road"
        else -> null
    }
}

class FloodReport(val edge: Int, val lat: Double, val lon: Double, val reportedAtMs: Long, val expiresAtMs: Long) {
    fun active(nowMs: Long) = nowMs in reportedAtMs until expiresAtMs
}

class FloodResult(val accepted: Boolean, val message: String, val report: FloodReport? = null)

/**
 * Rider flood reports (#59). A report closes the road edge nearest to the rider (the F term of the route cost) until it
 * expires, so [ChhayaRouter] steers around it. Uncertainty resolves toward safety: an old report is dropped only when it
 * expires, never by a newer "all clear" from a single rider. The road is treated as effectively closed, not forbidden: if the
 * only way is through it the route still exists and says so.
 */
class FloodReports(private val reports: MutableList<FloodReport> = ArrayList()) {
    companion object {
        /** Reports last three hours: waterlogging on city roads usually clears in that time; a fresh report renews it. */
        const val TTL_MS = 3 * 3600_000L
        const val MAX_SNAP_M = 60.0

        fun fromJson(s: String): FloodReports = try {
            val a = JSONArray(s)
            FloodReports((0 until a.length()).map { val o = a.getJSONObject(it); FloodReport(o.getInt("e"), o.getDouble("lat"), o.getDouble("lon"), o.getLong("at"), o.getLong("until")) }.toMutableList())
        } catch (_: Exception) { FloodReports() }
    }

    @Synchronized fun toJson(): String = JSONArray(reports.map { JSONObject().put("e", it.edge).put("lat", it.lat).put("lon", it.lon).put("at", it.reportedAtMs).put("until", it.expiresAtMs) }).toString()

    @Synchronized fun active(nowMs: Long): List<FloodReport> { reports.removeAll { nowMs >= it.expiresAtMs }; return reports.filter { it.active(nowMs) } }

    @Synchronized fun closedEdges(nowMs: Long): Set<Int> = active(nowMs).map { it.edge }.toSet()

    /** One tap: the photo has been checked by [FloodPhotoCheck]; [photoProblem] is its verdict. */
    @Synchronized fun report(g: RoadGraph, lat: Double, lon: Double, nowMs: Long, photoProblem: String?): FloodResult {
        if (photoProblem != null) return FloodResult(false, photoProblem)
        val e = g.nearestEdge(lat, lon, MAX_SNAP_M) ?: return FloodResult(false, "No mapped road within ${MAX_SNAP_M.toInt()} m of you")
        reports.removeAll { it.edge == e }
        val r = FloodReport(e, lat, lon, nowMs, nowMs + TTL_MS)
        reports += r
        return FloodResult(true, "Reported flooded. Routes avoid this road for 3 hours.", r)
    }
}

/** The one set of flood reports on this phone, kept in private preferences so a restart does not forget a closed road. */
object FloodStore {
    private var instance: FloodReports? = null

    @Synchronized fun get(ctx: android.content.Context): FloodReports = instance ?: FloodReports.fromJson(
        ctx.getSharedPreferences("thakaan_flood", android.content.Context.MODE_PRIVATE).getString("reports", "[]") ?: "[]",
    ).also { instance = it }

    fun save(ctx: android.content.Context) {
        val f = instance ?: return
        ctx.getSharedPreferences("thakaan_flood", android.content.Context.MODE_PRIVATE).edit().putString("reports", f.toJson()).apply()
    }
}

/**
 * Rain-onset cue: no barometer on the iQOO 15, so it is a cue, not a forecast. Daylight, a damp forecast hour (humidity or
 * cloud cover high) and a sharp fall of the light sensor within ten minutes together say "rain may be starting". Errs toward
 * warning: any of the inputs missing gives NONE only when nothing else points to rain.
 */
object RainCue {
    enum class Level { NONE, POSSIBLE, LIKELY }

    fun detect(sunElevationDeg: Double, humidityPct: Double?, cloudPct: Double?, luxNow: Double?, luxTenMinAgo: Double?): Level {
        if (sunElevationDeg < 12.0) return Level.NONE // after dusk a light drop means nothing
        val damp = (humidityPct != null && humidityPct >= 80.0) || (cloudPct != null && cloudPct >= 85.0)
        val drop = luxNow != null && luxTenMinAgo != null && luxTenMinAgo > 2000.0 && luxNow < 0.45 * luxTenMinAgo
        return when {
            damp && drop -> Level.LIKELY
            drop || (humidityPct != null && humidityPct >= 90.0 && (cloudPct ?: 0.0) >= 85.0) -> Level.POSSIBLE
            else -> Level.NONE
        }
    }
}
