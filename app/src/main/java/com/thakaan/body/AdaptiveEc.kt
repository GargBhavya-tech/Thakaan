package com.thakaan.body

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Adaptive R (#29). A moving average of innovations (measured HR minus model HR). When it stays biased, humidity and
 * circulatory drift are being tracked as P9 / P10 rather than as core temperature, so R is inflated and the core trend
 * stops running away.
 *
 * SAFETY: inflating R can make the displayed core trend LOWER than the plain filter. The invariant says information
 * handling must never lower the level, so [ctForAnchors] is the max of the adaptive and the plain estimates and is the
 * only value the anchors may use. The adaptive value is for the trend display and the drift drivers.
 */
class AdaptiveEcTemp(
    private val model: ObservationModel = PublishedQuadratic,
    start: ShiftStart = ShiftStart(EcTempParams.CT0, EcTempParams.V0, 1.0, 0),
    var adaptive: Boolean = true,
    private val window: Int = 4,
    private val biasBpm: Double = 6.0,
) {
    private val plain = EcTempFilter(model, false, start)
    private val innovations = ArrayDeque<Double>()
    private var inflate = 1.0
        private var ctAdaptive = start.ct0

    val rInflation: Double get() = inflate
    /** Largest inflation applied so far this shift (for diagnostics and tests). */
    var peakInflation: Double = 1.0; private set
    val ctPlain: Double get() = plain.ct
    val ctTrend: Double get() = if (adaptive) ctAdaptive else plain.ct
    val ctForAnchors: Double get() = max(plain.ct, ctAdaptive)

    private var adaptFilter = AdaptiveCore(model, start)

    private companion object { const val MAX_DRIFT_BPM = 15.0 }

    /** Part of the heart rate attributed to circulatory / humidity drift (P10) rather than to core temperature, bpm. */
    var driftBpm: Double = 0.0; private set

    fun update(epochSeconds: Long, hr: Double?) {
        plain.update(epochSeconds, hr)
        var hrForAdaptive = hr
        if (hr != null && hr in EcTempParams.HR_MIN..EcTempParams.HR_MAX) {
            innovations.addLast(hr - model.h(adaptFilter.ct))
            while (innovations.size > window) innovations.removeFirst()
            val bias = if (innovations.size >= window) innovations.average() else 0.0
            if (abs(bias) > biasBpm) {
                inflate = min(10.0, 1.0 + ((abs(bias) - biasBpm) / biasBpm) * 4.0)
                driftBpm = (0.7 * bias).coerceIn(-MAX_DRIFT_BPM, MAX_DRIFT_BPM)
            } else {
                inflate = 1.0
                driftBpm *= 0.9
            }
            if (adaptive) {
                peakInflation = max(peakInflation, inflate)
                hrForAdaptive = hr - driftBpm
            }
        }
        adaptFilter.update(epochSeconds, hrForAdaptive, EcTempParams.SIGMA2 * (if (adaptive) inflate else 1.0))
        ctAdaptive = adaptFilter.ct
    }
    /** Minimal EKF carrying its own R per update, so inflation takes effect from the next reading. */
    private class AdaptiveCore(private val model: ObservationModel, start: ShiftStart) {
        var ct = start.ct0; private set
        var v = start.v0; private set
        private var last: Long? = null
        fun update(epochSeconds: Long, hr: Double?, r: Double) {
            val idx = epochSeconds / EcTempParams.STEP_SECONDS
            val l = last
            if (l != null) repeat((idx - l - 1).toInt()) { val s = EcTemp.step(ct, v, null, model); ct = s.ct; v = s.v }
            val s = EcTemp.step(ct, v, hr, model, EcTempParams.GAMMA2, r)
            ct = s.ct; v = s.v
            last = idx
        }
    }
}




