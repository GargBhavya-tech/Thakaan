package com.thakaan.exposure

import com.thakaan.motion.MoveState
import com.thakaan.motion.StateStamp

/**
 * E7: a real rest is shade + still for more than 5 minutes. A long red light in the sun is not a rest, and unknown
 * shade counts as sun (invariant 3), so losing the light reading can never grant a rest.
 */
class RestLedger(
    private val realRestSec: Double = 300.0,
    /** Assumption (the Build Map gives no number): after this many minutes without a real rest, rest debt starts to build. */
    private val workBlockMinutes: Double = 60.0,
) {
    var currentRestSec = 0.0
        private set
    var lastRestLengthSec = 0.0
        private set
    var lastRealRestEndNanos: Long? = null
        private set
    var onRealRest = false
        private set
    private var restStartNanos: Long? = null
    private var firstSeenNanos: Long? = null
    private var lastNanos: Long? = null

    fun update(tNanos: Long, stamp: StateStamp, light: LightEstimate) {
        if (firstSeenNanos == null) firstSeenNanos = tNanos
        val dt = lastNanos?.let { (tNanos - it) / 1e9 } ?: 0.0
        lastNanos = tNanos
        val still = stamp.state == MoveState.STOPPED || stamp.state == MoveState.RESTING
        val inShade = !light.effectiveSun && light.state != SunState.UNKNOWN
        if (still && inShade) {
            if (restStartNanos == null) restStartNanos = tNanos
            currentRestSec += dt
            if (currentRestSec >= realRestSec) { onRealRest = true; lastRealRestEndNanos = tNanos; lastRestLengthSec = currentRestSec }
        } else {
            currentRestSec = 0.0; restStartNanos = null; onRealRest = false
        }
    }

    /** Minutes since the last real rest ended (or since the first reading, if the worker has not rested yet). */
    fun minutesSinceRest(nowNanos: Long): Double {
        val ref = lastRealRestEndNanos ?: firstSeenNanos ?: return 0.0
        return (nowNanos - ref) / 60e9
    }

    /** Minutes beyond the work block without a real rest. Zero means no debt. */
    fun restDebtMinutes(nowNanos: Long): Double = (minutesSinceRest(nowNanos) - workBlockMinutes).coerceAtLeast(0.0)
}

enum class DrinkSource { TAP, VOICE, WATER_STOP }

class Drink(val tNanos: Long, val ml: Double, val source: DrinkSource)

/** E8: the drink log. A missed log inflates the hydration deficit, so entries carry their source and the UI shows a range. */
class DrinkLog {
    private val items = ArrayList<Drink>()
    val drinks: List<Drink> get() = items

    fun log(tNanos: Long, source: DrinkSource, ml: Double = DEFAULT_ML) { items += Drink(tNanos, ml, source) }

    fun totalMl(): Double = items.sumOf { it.ml }

    fun mlSince(tNanos: Long): Double = items.filter { it.tNanos >= tNanos }.sumOf { it.ml }

    fun lastDrinkNanos(): Long? = items.maxOfOrNull { it.tNanos }

    companion object {
        /** Assumption: a typical sip-and-gulp at a stop. Refined by the "did you drink?" nudge. */
        const val DEFAULT_ML = 250.0
    }
}

/** Offline matching of "pani piya"-style phrases (after speech recognition) in Hindi, Kannada, Tamil, Telugu and English. */
object DrinkPhrases {
    private val phrases = listOf(
        // Hindi (Latin and Devanagari)
        "pani piya", "paani piya", "pani pi liya", "paani pi liya", "पानी पिया", "पानी पी लिया",
        // Kannada
        "neeru kudide", "nīru kudide", "ನೀರು ಕುಡಿದೆ", "ನೀರು ಕುಡಿದಿದ್ದೇನೆ",
        // Tamil
        "thanni kudichen", "thanni kudicha", "தண்ணீர் குடித்தேன்",
        // Telugu
        "neellu tagaanu", "neellu taagaanu", "నీళ్ళు తాగాను", "నీరు తాగాను",
        // English
        "drank water", "had water", "i drank", "drink logged",
    )

    fun matches(utterance: String): Boolean {
        val s = utterance.trim().lowercase().replace(Regex("\\s+"), " ")
        return phrases.any { s.contains(it.lowercase()) }
    }
}
