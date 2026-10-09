package com.thakaan.exposure

import com.thakaan.core.Provenance

enum class IrradianceMethod { SENSOR, CLEAR_SKY_MODEL }

class IrradianceEstimate(
    /** Global horizontal irradiance in W/m^2, the quantity the Liljegren WBGT model takes. */
    val wm2: Double,
    val method: IrradianceMethod,
    val saturated: Boolean,
    val provenance: Provenance,
)

/** E15: lux to W/m^2 with a saturation check. Never uses the phone body's temperature (that idea was dropped in v3). */
object Irradiance {
    private const val MAX_TILT_GAIN = 2.5

    fun estimate(lux: Double?, sun: Sun, light: LightEstimate, cloudPct: Double?, provenance: Provenance = light.provenance): IrradianceEstimate {
        val model = SolarPosition.clearSkyGhi(sun.elevationDeg) * SolarPosition.cloudFactor(cloudPct)
        if (sun.elevationDeg <= 0.0) return IrradianceEstimate(0.0, IrradianceMethod.CLEAR_SKY_MODEL, false, provenance)
        if (lux == null) return IrradianceEstimate(model, IrradianceMethod.CLEAR_SKY_MODEL, false, provenance)
        if (lux >= LightRules.SENSOR_SATURATION_LUX) {
            // the sensor cannot read this bright: switch to the sun-position model rather than trusting a pinned number
            return IrradianceEstimate(model, IrradianceMethod.CLEAR_SKY_MODEL, true, provenance)
        }
        val measured = lux / LightRules.LUX_PER_WM2
        val cos = light.cosIncidence
        if (cos == null || !light.sensorTrusted) {
            // sensor not facing the sun: its reading under-counts; take the larger of reading and model when sun is judged present
            val w = if (light.effectiveSun) maxOf(measured, model) else measured
            return IrradianceEstimate(w, if (w == measured) IrradianceMethod.SENSOR else IrradianceMethod.CLEAR_SKY_MODEL, false, provenance)
        }
        // cosine tilt correction back to the horizontal plane: E_h = E_meas * sin(el) / cos(incidence), gain capped
        val gain = (sun.sinElevation / cos).coerceIn(0.4, MAX_TILT_GAIN)
        return IrradianceEstimate(measured * gain, IrradianceMethod.SENSOR, false, provenance)
    }
}

/** E2: how much direct sun the worker has had this shift. */
class SunDose {
    var secondsInSun = 0.0
        private set
    /** Minutes weighted by solar elevation: an hour of low morning sun counts less than an hour at noon. */
    var weightedMinutes = 0.0
        private set
    var energyWhPerM2 = 0.0
        private set
    var provenance: Provenance = Provenance.REAL
        private set

    val minutesInSun: Double get() = secondsInSun / 60.0

    fun update(dtSec: Double, wm2: Double, effectiveSun: Boolean, sun: Sun, prov: Provenance) {
        provenance = Provenance.combine(provenance, prov)
        if (!effectiveSun || sun.elevationDeg <= 0.0) return
        secondsInSun += dtSec
        weightedMinutes += dtSec / 60.0 * sun.sinElevation.coerceIn(0.0, 1.0)
        energyWhPerM2 += wm2 * dtSec / 3600.0
    }
}
