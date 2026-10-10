package com.thakaan.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import com.thakaan.risk.AlertPlan
import com.thakaan.risk.AlertLadder
import com.thakaan.risk.Lang
import java.util.Locale

private val CHECK_DUE_LINES = mapOf(
    Lang.EN to "Time for your health check.",
    Lang.HI to "अब अपनी जाँच करने का समय है।",
    Lang.KN to "ಈಗ ನಿಮ್ಮ ಪರೀಕ್ಷೆ ಮಾಡುವ ಸಮಯ.",
    Lang.TA to "உங்கள் பரிசோதனைக்கான நேரம் இது.",
    Lang.TE to "మీ పరీక్ష చేసుకునే సమయం ఇది.",
)

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

    /** The reminder that a check is due: vibration, a spoken line in the worker's language, and a notification for a phone in a pocket. */
    fun checkDue(lang: Lang) {
        vibrate(longArrayOf(0, 300, 200, 300))
        speak(CHECK_DUE_LINES.getValue(lang), lang)
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("checks", "Check reminders", android.app.NotificationManager.IMPORTANCE_HIGH))
        val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)?.let { android.app.PendingIntent.getActivity(ctx, 0, it, android.app.PendingIntent.FLAG_IMMUTABLE) }
        val n = androidx.core.app.NotificationCompat.Builder(ctx, "checks").setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Time for your check").setContentText("A short check keeps you safe in the heat.").setAutoCancel(true).setContentIntent(open).build()
        nm.notify(1002, n)
    }

    /** Per language: whether the on-device engine has a voice. Used by Diagnostics so a missing voice is visible before a shift. */
    fun voiceStatus(): List<Pair<Lang, String>> = Lang.entries.map { l ->
        l to when {
            !ready -> "engine not ready"
            else -> when (tts.isLanguageAvailable(Locale.forLanguageTag(l.tag))) {
                TextToSpeech.LANG_AVAILABLE, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "voice installed"
                TextToSpeech.LANG_MISSING_DATA -> "voice data missing"
                else -> "not supported"
            }
        }
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
