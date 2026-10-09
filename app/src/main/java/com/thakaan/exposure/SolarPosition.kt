package com.thakaan.exposure

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

class Sun(
    /** Degrees clockwise from true north. */
    val azimuthDeg: Double,
    /** Degrees above the horizon (negative at night). */
    val elevationDeg: Double,
) {
    val sinElevation: Double get() = sin(Math.toRadians(elevationDeg))
}

/** NOAA solar position algorithm. Accurate to a fraction of a degree, which is far more than shade routing needs. */
object SolarPosition {
    fun at(epochSeconds: Long, latDeg: Double, lonDeg: Double): Sun {
        val jd = epochSeconds / 86400.0 + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = mod360(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val mr = Math.toRadians(m)
        val c = sin(mr) * (1.914602 - t * (0.004817 + 0.000014 * t)) + sin(2 * mr) * (0.019993 - 0.000101 * t) + sin(3 * mr) * 0.000289
        val trueLong = l0 + c
        val omega = 125.04 - 1934.136 * t
        val lambda = Math.toRadians(trueLong - 0.00569 - 0.00478 * sin(Math.toRadians(omega)))
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = Math.toRadians(eps0 + 0.00256 * cos(Math.toRadians(omega)))
        val decl = asin(sin(eps) * sin(lambda))
        val y = tan(eps / 2).pow(2)
        val l0r = Math.toRadians(l0)
        val eqTime = 4.0 * Math.toDegrees(
            y * sin(2 * l0r) - 2 * e * sin(mr) + 4 * e * y * sin(mr) * cos(2 * l0r) - 0.5 * y * y * sin(4 * l0r) - 1.25 * e * e * sin(2 * mr),
        )
        val minutesUtc = ((epochSeconds % 86400) + 86400) % 86400 / 60.0
        val trueSolarTime = (minutesUtc + eqTime + 4.0 * lonDeg).mod(1440.0)
        val hourAngle = Math.toRadians(trueSolarTime / 4.0 - 180.0)
        val lat = Math.toRadians(latDeg)
        val cosZ = (sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        val zenith = acos(cosZ)
        val elevation = 90.0 - Math.toDegrees(zenith)
        val sinZ = sin(zenith)
        val az = if (sinZ < 1e-9) 180.0 else {
            val a = Math.toDegrees(acos(((sin(lat) * cosZ - sin(decl)) / (cos(lat) * sinZ)).coerceIn(-1.0, 1.0)))
            if (hourAngle > 0) (a + 180.0).mod(360.0) else (540.0 - a).mod(360.0)
        }
        return Sun(az, elevation)
    }

    private fun mod360(x: Double) = x.mod(360.0)

    /** Haurwitz clear-sky global horizontal irradiance in W/m^2 for a given sun elevation. */
    fun clearSkyGhi(elevationDeg: Double): Double {
        if (elevationDeg <= 0.0) return 0.0
        val cosZ = sin(Math.toRadians(elevationDeg)).coerceAtLeast(0.02)
        return 1098.0 * cosZ * exp(-0.059 / cosZ)
    }

    /** Kasten and Czeplak cloud correction: fraction of clear-sky irradiance that reaches the ground, [cloudPct] 0..100. */
    fun cloudFactor(cloudPct: Double?): Double {
        if (cloudPct == null) return 1.0 // unknown cloud: assume clear (the higher load: fail safe)
        val n = (cloudPct / 100.0).coerceIn(0.0, 1.0)
        return 1.0 - 0.75 * n.pow(3.4)
    }
}
