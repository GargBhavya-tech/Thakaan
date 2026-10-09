package com.thakaan.exposure

/** Raw JNI entry into the lwbgt (Liljegren) C library. Loading can fail on a host JVM, which is expected. */
class WbgtNative {
    external fun calc(
        year: Int, month: Int, day: Int, hour: Int, minute: Int, gmtHours: Int, averagingMinutes: Int,
        lat: Double, lon: Double, solar: Double, pressureHpa: Double, tempC: Double, rhPercent: Double,
        windMs: Double, windHeightM: Double, vertDiffC: Double, urban: Int,
    ): DoubleArray?

    companion object {
        val available: Boolean = try { System.loadLibrary("thakaan_wbgt"); true } catch (_: Throwable) { false }
    }
}

class WbgtOutput(val wbgtC: Double, val globeC: Double, val naturalWetBulbC: Double, val windMs: Double)

class WbgtInput(
    val epochUtcMs: Long, val latDeg: Double, val lonDeg: Double,
    val solarWm2: Double, val pressureHpa: Double, val airTempC: Double, val rhPercent: Double, val windMs: Double,
)

/**
 * WBGT by day uses Liljegren only (never ACSM87, never phone body temperature). When the native library or the inputs
 * are unusable, the answer is null and the risk engine treats missing WBGT as hot (fail-safe), not as a mild default.
 */
class Wbgt(private val backend: (WbgtInput) -> DoubleArray? = NativeBackend) {

    fun compute(i: WbgtInput): WbgtOutput? {
        if (!validInputs(i)) return null
        val r = try { backend(i) } catch (_: Throwable) { null } ?: return null
        if (r.size < 5 || r[0] != 0.0) return null
        val out = WbgtOutput(wbgtC = r[1], globeC = r[2], naturalWetBulbC = r[3], windMs = r[4])
        return if (out.wbgtC.isFinite() && out.wbgtC > -50.0 && out.wbgtC < 70.0) out else null
    }

    companion object {
        /** The C library does not reject out-of-range humidity (RH 150% still returned 43 C), so we do. */
        fun validInputs(i: WbgtInput): Boolean =
            i.airTempC in -30.0..60.0 && i.rhPercent in 0.0..100.0 && i.solarWm2 in 0.0..1500.0 &&
                i.windMs in 0.0..60.0 && i.pressureHpa in 500.0..1100.0 && i.latDeg in -66.0..66.0 && i.lonDeg in -180.0..180.0 &&
                listOf(i.airTempC, i.rhPercent, i.solarWm2, i.windMs, i.pressureHpa, i.latDeg, i.lonDeg).all { it.isFinite() }

        val NativeBackend: (WbgtInput) -> DoubleArray? = backend@{ i ->
            if (!WbgtNative.available) return@backend null
            val c = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC")).apply { timeInMillis = i.epochUtcMs }
            WbgtNative().calc(
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE), 0, 10,
                i.latDeg, i.lonDeg, i.solarWm2, i.pressureHpa, i.airTempC, i.rhPercent, i.windMs, 2.0, -0.5, 1,
            )
        }
    }
}
