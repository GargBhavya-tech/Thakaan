package com.thakaan.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.thakaan.risk.AlertPlan
import com.thakaan.risk.AlertLadder
import com.thakaan.risk.Lang
import java.util.Locale

/**
 * Speaks and vibrates an [AlertPlan] on the phone (#40). Speech uses the on-device TextToSpeech engine, so it works with
 * no network. If the voice for the worker's language is not installed it falls back to English and says so in
 * [lastFallback], which Diagnostics shows (a missing Kannada or Telugu voice must be visible, not silent). DEVICE-UNTESTED.
 */
class AlertDelivery(private val ctx: Context) {
    private var ready = false
    var lastFallback: String? = null; private set
    private val tts: TextToSpeech = TextToSpeech(ctx) { status -> ready = status == TextToSpeech.SUCCESS }

    fun deliver(plan: AlertPlan) {
        vibrate(plan.haptic)
        plan.speech?.let { speak(it, plan.speechLang) }
    }

    fun collapsePrompt(lang: Lang) = speak(AlertLadder.collapsePrompt(lang), lang)

    fun speak(text: String, lang: Lang) {
        if (!ready) { lastFallback = "speech engine not ready"; return }
        var r = tts.setLanguage(Locale.forLanguageTag(lang.tag))
        if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
            lastFallback = "no ${lang.tag} voice installed; speaking English"
            r = tts.setLanguage(Locale.forLanguageTag(Lang.EN.tag))
        } else lastFallback = null
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "thakaan-alert")
    }

    private fun vibrate(pattern: LongArray) {
        if (pattern.isEmpty()) return
        val v = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        v.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    fun shutdown() { tts.shutdown() }
}
