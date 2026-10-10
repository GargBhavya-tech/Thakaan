package com.thakaan.brain

import java.util.Random

/** One recognised word with timing from the recogniser (forced alignment against the shown code). */
class SpokenWord(val text: String, val startSec: Double, val endSec: Double, val confidence: Double = 1.0)

class SpeechCodeResult(
    val correct: Boolean,
    val wrongDigits: Int,
    /** Digits per second of speaking time (pauses excluded). */
    val articulationRate: Double,
    val longPauses: Int,
    val snrDb: Double?,
    /** Jitter, shimmer and CPP are only meaningful when the room is quiet. */
    val voiceQualityAllowed: Boolean,
    val valid: Boolean,
    val reason: String = "",
)

/**
 * N3: show a random 4-digit code, the worker says it. The value here is catching confusion (wrong digits) and slurring
 * (slow rate, long pauses), not voice biomarkers, which do not survive traffic noise.
 */
object SpeechCode {
    const val QUIET_SNR_DB = 20.0
    const val MIN_SNR_DB = 8.0
    const val PAUSE_SEC = 0.8

    fun newCode(rng: Random = java.security.SecureRandom()): String = (1..4).joinToString("") { rng.nextInt(10).toString() }

    private val DIGITS: Map<String, Int> = buildMap {
        val en = listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine")
        val hi = listOf("shunya", "ek", "do", "teen", "char", "paanch", "chhe", "saat", "aath", "nau")
        val hiNative = listOf("शून्य", "एक", "दो", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ")
        val kn = listOf("sonne", "ondu", "eradu", "mooru", "naalku", "aidu", "aaru", "elu", "entu", "ombattu")
        val knNative = listOf("ಸೊನ್ನೆ", "ಒಂದು", "ಎರಡು", "ಮೂರು", "ನಾಲ್ಕು", "ಐದು", "ಆರು", "ಏಳು", "ಎಂಟು", "ಒಂಬತ್ತು")
        for (lists in listOf(en, hi, hiNative, kn, knNative)) lists.forEachIndexed { d, w -> put(w, d) }
        for (d in 0..9) put(d.toString(), d)
        put("oh", 0); put("nil", 0); put("panch", 5); put("chaar", 4); put("che", 6); put("six", 6)
    }

    fun digitOf(word: String): Int? = DIGITS[word.trim().lowercase().trim('.', ',', '!', '?')]

    fun check(code: String, words: List<SpokenWord>, snrDb: Double?): SpeechCodeResult {
        if (snrDb != null && snrDb < MIN_SNR_DB) {
            return SpeechCodeResult(false, 0, 0.0, 0, snrDb, false, false, "too noisy to judge; ask again or use another test")
        }
        val heard = words.mapNotNull { w -> digitOf(w.text)?.let { it to w } }
        // a spoken digit may arrive as one multi-digit token ("1234"): expand it using the same timing
        val flat = ArrayList<Pair<Int, SpokenWord>>()
        for (w in words) {
            val t = w.text.trim()
            if (t.length > 1 && t.all { it.isDigit() }) t.forEach { flat += (it - '0') to w } else digitOf(t)?.let { flat += it to w }
        }
        val digits = if (flat.isNotEmpty()) flat else heard
        if (digits.isEmpty()) return SpeechCodeResult(false, code.length, 0.0, 0, snrDb, false, true, "no digits heard")
        var wrong = 0
        for (i in code.indices) if (digits.getOrNull(i)?.first != code[i] - '0') wrong++
        wrong += maxOf(0, digits.size - code.length)

        val spoken = digits.map { it.second }.distinct()
        val span = (spoken.last().endSec - spoken.first().startSec).coerceAtLeast(0.1)
        var pauseTime = 0.0; var longPauses = 0
        for (i in 1 until spoken.size) {
            val gap = spoken[i].startSec - spoken[i - 1].endSec
            if (gap > PAUSE_SEC) longPauses++
            if (gap > 0.3) pauseTime += gap
        }
        val rate = digits.size / (span - pauseTime).coerceAtLeast(0.3)
        return SpeechCodeResult(wrong == 0, wrong, rate, longPauses, snrDb, snrDb != null && snrDb >= QUIET_SNR_DB, true)
    }

    fun toResults(r: SpeechCodeResult, tNanos: Long, hour: Double, device: String): List<BrainResult> =
        if (!r.valid) emptyList() else listOf(
            BrainResult(BrainTest.SPEECH_RATE, r.articulationRate, tNanos, hour, device),
            BrainResult(BrainTest.SPEECH_WRONG, r.wrongDigits.toDouble(), tNanos, hour, device),
        )
}
