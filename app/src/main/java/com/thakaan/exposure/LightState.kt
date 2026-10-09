package com.thakaan.exposure

import com.thakaan.core.Provenance
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sqrt

enum class SunState { SUN, SHADE, OVERCAST, DARK, UNKNOWN }

/** Everything the E1 classifier may know about this second. Any field except [epochSec] and the position may be missing. */
class LightInput(
    val tNanos: Long,
    val epochSec: Long,
    val latDeg: Double,
    val lonDeg: Double,
    val lux: Double?,
    val cctK: Double?,
    /** Accelerometer low-pass (the gravity vector in device axes). Null = unknown tilt. */
    val gravity: DoubleArray?,
    val headingDeg: Double?,
    val speedMs: Double?,
    val cloudPct: Double?,
    /** From GNSS sky view (E12): is the satellite pattern around the sun blocked? Null = unknown. */
    val sunBlockedBySky: Boolean?,
    val provenance: Provenance = Provenance.REAL,
)

class LightEstimate(
    val state: SunState,
    /** True when the rider should be counted as in direct sun. UNKNOWN counts as sun (invariant 3). */
    val effectiveSun: Boolean,
    val confidence: Double,
    val reason: String,
    /** cosine of the angle between the sensor normal and the sun; null if tilt or heading is unknown. */
    val cosIncidence: Double?,
    /** False when the sensor cannot be reading direct sun (facing away, or the rider's body is in the way). */
    val sensorTrusted: Boolean,
    val provenance: Provenance,
)

object LightRules {
    const val SENSOR_SATURATION_LUX = 60_000.0 // iQOO 15 light sensor tops out at 66,110 lux (docs/SMOKE_TEST.md)
    const val LUX_PER_WM2 = 110.0
    const val NIGHT_ELEVATION_DEG = 3.0
    const val SUN_RATIO = 0.45
    const val SHADE_RATIO = 0.12
    const val CCT_SUN_MAX = 6300.0
    const val CCT_SHADE_MIN = 7000.0
    const val FACING_MIN_COS = 0.2
    /** A low sun behind the rider is hidden from a phone that faces the rider. */
    const val BODY_SHADOW_CONE_DEG = 60.0
    const val BODY_SHADOW_MAX_ELEVATION_DEG = 45.0
}

class SensorGeometry(val cosIncidence: Double?, val bodyBlocksSun: Boolean) {
    val facingSun: Boolean get() = cosIncidence != null && cosIncidence >= LightRules.FACING_MIN_COS && !bodyBlocksSun
}

/**
 * E1: sun, shade, overcast. The light sensor faces the rider, so with a low sun behind them the rider's own body blocks
 * it: the sensor reads "shade" while the rider bakes. In that geometry, and whenever the sensor cannot be facing the sun,
 * a dim reading is not believed unless the GNSS sky view says the sun's direction is blocked. Losing any input only ever
 * moves the answer toward SUN.
 */
object LightClassifier {
    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360.0
        return if (d > 180.0) 360.0 - d else d
    }

    fun geometry(gravity: DoubleArray?, headingDeg: Double?, sun: Sun): SensorGeometry {
        if (gravity == null) return SensorGeometry(null, false)
        val gl = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (gl < 1e-6) return SensorGeometry(null, false)
        val tilt = Math.toDegrees(acos((abs(gravity[2]) / gl).coerceIn(0.0, 1.0))) // 0 = flat, 90 = upright
        val el = Math.toRadians(sun.elevationDeg)
        val flat = sun.sinElevation
        if (tilt <= 20.0) return SensorGeometry(flat, false)
        if (headingDeg == null) return if (tilt >= 70.0) SensorGeometry(null, false) else SensorGeometry(flat * 0.5, false)

        // upright and facing the rider: the sensor normal points opposite to the travel direction
        val facingAz = (headingDeg + 180.0) % 360.0
        val off = angleDiff(sun.azimuthDeg, facingAz)
        val upright = cos(el) * cos(Math.toRadians(off))
        val behindRider = off < LightRules.BODY_SHADOW_CONE_DEG && sun.elevationDeg < LightRules.BODY_SHADOW_MAX_ELEVATION_DEG
        if (tilt >= 70.0) return SensorGeometry(upright, behindRider)
        val w = (tilt - 20.0) / 50.0
        return SensorGeometry(flat * (1 - w) + upright * w, behindRider && w > 0.5)
    }

    fun classifyInstant(i: LightInput): LightEstimate {
        val sun = SolarPosition.at(i.epochSec, i.latDeg, i.lonDeg)
        if (sun.elevationDeg < LightRules.NIGHT_ELEVATION_DEG) {
            return LightEstimate(SunState.DARK, false, 0.9, "sun below the horizon", null, true, i.provenance)
        }
        val lux = i.lux
            ?: return LightEstimate(SunState.UNKNOWN, true, 0.2, "no light reading: assumed sun", null, false, i.provenance)
        val geo = geometry(i.gravity, i.headingDeg, sun)
        val cosInc = geo.cosIncidence

        if (lux >= LightRules.SENSOR_SATURATION_LUX) {
            return LightEstimate(SunState.SUN, true, 0.95, "light sensor saturated: full sun", cosInc, true, i.provenance)
        }

        val ghi = SolarPosition.clearSkyGhi(sun.elevationDeg) * SolarPosition.cloudFactor(i.cloudPct)

        if (!geo.facingSun) {
            val why = if (geo.bodyBlocksSun) "sun is behind the rider: sensor is in the rider's shadow" else "sensor not facing the sun"
            val deepDark = lux < 0.3 * 0.15 * ghi * LightRules.LUX_PER_WM2 * 0.5 // far below even sky-only light
            return when {
                i.sunBlockedBySky == true -> LightEstimate(SunState.SHADE, false, 0.6, "sun direction blocked per GNSS sky view", cosInc, false, i.provenance)
                ghi > 250.0 && !deepDark -> LightEstimate(SunState.SUN, true, 0.4, "$why: assumed sun", cosInc, false, i.provenance)
                ghi > 250.0 -> LightEstimate(SunState.SHADE, false, 0.5, "$why but very dim: deep shade", cosInc, false, i.provenance)
                else -> LightEstimate(SunState.OVERCAST, false, 0.4, "weak sun, $why", cosInc, false, i.provenance)
            }
        }

        val direct = ghi / max(sun.sinElevation, 0.15) * 0.9
        val expected = (direct * cosInc!!.coerceAtLeast(0.0) + 0.15 * ghi) * LightRules.LUX_PER_WM2
        val ratio = if (expected > 1.0) lux / expected else 0.0

        val sunlike = ratio >= LightRules.SUN_RATIO
        val shadelike = ratio < LightRules.SHADE_RATIO
        val cct = i.cctK
        return when {
            sunlike && (cct == null || cct <= 6800.0) -> LightEstimate(SunState.SUN, true, if (cct == null) 0.7 else 0.9, "bright, sun-coloured light", cosInc, true, i.provenance)
            sunlike -> LightEstimate(SunState.OVERCAST, false, 0.5, "bright but blue light: sky only", cosInc, true, i.provenance)
            shadelike -> {
                if (i.cloudPct != null && i.cloudPct >= 80.0 && lux > 3000.0) LightEstimate(SunState.OVERCAST, false, 0.6, "heavy cloud", cosInc, true, i.provenance)
                else LightEstimate(SunState.SHADE, false, if (cct != null && cct >= LightRules.CCT_SHADE_MIN) 0.9 else 0.7, "far dimmer than open sun", cosInc, true, i.provenance)
            }
            else -> when {
                cct != null && cct <= LightRules.CCT_SUN_MAX && ratio >= 0.25 -> LightEstimate(SunState.SUN, true, 0.55, "sun-coloured, moderately bright", cosInc, true, i.provenance)
                i.cloudPct != null && i.cloudPct >= 70.0 -> LightEstimate(SunState.OVERCAST, false, 0.6, "cloudy sky", cosInc, true, i.provenance)
                cct != null && cct >= LightRules.CCT_SHADE_MIN -> LightEstimate(SunState.SHADE, false, 0.65, "blue-sky light: open shade", cosInc, true, i.provenance)
                else -> LightEstimate(SunState.SUN, true, 0.35, "ambiguous: assumed sun", cosInc, true, i.provenance)
            }
        }
    }
}

/**
 * Adds hysteresis to [LightClassifier]: moving INTO sun is accepted after 2 s, moving OUT of sun (into shade) only after
 * 5 s of agreement, so a passing shadow or a glance away does not lower the exposure, but real sun is noticed quickly.
 */
class LightTracker(private val toSunSec: Int = 2, private val toShadeSec: Int = 5) {
    var current: LightEstimate? = null
        private set
    private var pendingCount = 0

    fun update(i: LightInput): LightEstimate {
        val now = LightClassifier.classifyInstant(i)
        val cur = current
        if (cur == null) { current = now; return now }
        if (now.effectiveSun == cur.effectiveSun) {
            pendingCount = 0
            current = now
            return now
        }
        pendingCount++
        val need = if (now.effectiveSun) toSunSec else toShadeSec
        if (pendingCount >= need) { current = now; pendingCount = 0 }
        return current!!
    }
}
