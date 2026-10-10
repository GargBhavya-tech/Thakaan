package com.thakaan.body

import kotlin.math.max

/** Body-mass water deficit as a range, in percent of body mass. A missed drink log inflates the deficit, so we show a band. */
class HydrationBand(val lowPct: Double, val midPct: Double, val highPct: Double) {
    val text: String get() = "%.1f to %.1f%%".format(java.util.Locale.ROOT, lowPct, highPct)
}

/**
 * Hydration deficit (P15, #25): water lost (PHS / Gagge sweat plus breathing and skin) minus logged drinks. 1 g = 1 ml.
 * Fail-safe: the mid value that feeds the filter and the risk engine is never below the true loss minus what was logged;
 * an unlogged drink makes the number too high, which errs towards earlier action, and the nudge asks about it.
 */
class HydrationLedger(private val bodyMassKg: Double) {
    private var lossG = 0.0
    private var drinkMl = 0.0
    private var minutes = 0
    private var lastDrinkMinute: Int? = null

    /** One minute of loss. Sweat from the heat-balance model, plus insensible water that rises with work. */
    fun addMinute(sweatGPerM2h: Double, bsaM2: Double, metabolicW: Double) {
        val sweat = max(0.0, sweatGPerM2h) * bsaM2 / 60.0
        val insensible = (30.0 + 0.1 * max(0.0, metabolicW - 100.0)) / 60.0
        lossG += sweat + insensible
        minutes++
    }

    fun logDrink(ml: Double) {
        require(ml > 0) { "a drink is positive millilitres" }
        drinkMl += ml
        lastDrinkMinute = minutes
    }

    fun band(): HydrationBand {
        fun pct(g: Double) = max(0.0, g) / (bodyMassKg * 1000.0) * 100.0
        val allowance = 100.0 * minutes / 60.0 // up to 100 ml an hour may have been drunk without logging
        val low = pct(0.8 * lossG - drinkMl - allowance)
        val mid = pct(lossG - 0.9 * drinkMl)
        val high = pct(1.2 * lossG - 0.8 * drinkMl)
        return HydrationBand(low, mid, high)
    }

    /** Ask "did you drink?" before escalating on hydration alone. */
    fun needsDrinkNudge(): Boolean {
        val b = band()
        val sinceDrink = minutes - (lastDrinkMinute ?: 0)
        return b.midPct >= 1.0 && sinceDrink >= 45
    }

    val totalLossMl: Double get() = lossG
    val totalDrinkMl: Double get() = drinkMl

    companion object {
        const val BPM_PER_PCT = 4.5
        const val CORE_C_PER_PCT = 0.175
    }
}

/** Observation model with a dehydration offset: HR is higher at the same core temperature when the body is dry. */
class DehydratedModel(private val base: ObservationModel, private val deficitPct: Double) : ObservationModel {
    private val offset = HydrationLedger.BPM_PER_PCT * deficitPct
    override fun h(ct: Double) = base.h(ct) + offset
    override fun slope(ct: Double) = base.slope(ct)
}

/** Extra core-temperature prior when the deficit grows: 0.175 C per 1% of body mass, applied as the deficit changes. */
fun hydrationCoreIncrement(previousMidPct: Double, midPct: Double): Double =
    HydrationLedger.CORE_C_PER_PCT * max(0.0, midPct - previousMidPct)
