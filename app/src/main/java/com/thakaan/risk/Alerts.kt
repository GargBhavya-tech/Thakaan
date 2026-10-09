package com.thakaan.risk

enum class Lang(val tag: String) { EN("en-IN"), HI("hi-IN"), KN("kn-IN"), TA("ta-IN"), TE("te-IN") }

enum class AlertScreen { NONE, AMBER_RING, ORANGE_BANNER, RED_TAKEOVER }

enum class AlertAction(val minutes: Int) {
    DRINK(1),
    SHADE_AND_DRINK(3),
    STOP_AND_SHADE(2),
}

/**
 * Everything one alert does. Every alert names ONE action, speaks it in the worker's language, and shows its time cost
 * (invariant 9). Danger also alerts the contact and the supervisor, and can never be snoozed.
 */
class AlertPlan(
    val level: Level,
    val action: AlertAction?,
    val speech: String?,
    val speechLang: Lang,
    /** Vibration pattern in ms: off, on, off, on, ... as for android.os.VibrationEffect.createWaveform. */
    val haptic: LongArray,
    val screen: AlertScreen,
    val bannerText: String?,
    val notifyContact: Boolean,
    val notifySupervisor: Boolean,
    val snoozeAllowed: Boolean,
    val snoozeMinutes: Int,
)

object AlertLadder {
    private val lines: Map<Lang, Map<AlertAction, String>> = mapOf(
        Lang.EN to mapOf(
            AlertAction.DRINK to "Drink some water now. It takes {n} minute.",
            AlertAction.SHADE_AND_DRINK to "Stop in the shade and drink water. It takes {n} minutes.",
            AlertAction.STOP_AND_SHADE to "Stop now and go to the nearest shade. It takes {n} minutes.",
        ),
        Lang.HI to mapOf(
            AlertAction.DRINK to "अभी पानी पीजिए। इसमें {n} मिनट लगेगा।",
            AlertAction.SHADE_AND_DRINK to "रुकिए और छाँव में पानी पीजिए। इसमें {n} मिनट लगेंगे।",
            AlertAction.STOP_AND_SHADE to "अभी रुकिए और सबसे पास की छाँव में जाइए। इसमें {n} मिनट लगेंगे।",
        ),
        Lang.KN to mapOf(
            AlertAction.DRINK to "ಈಗ ನೀರು ಕುಡಿಯಿರಿ. ಇದಕ್ಕೆ {n} ನಿಮಿಷ ಬೇಕು.",
            AlertAction.SHADE_AND_DRINK to "ನಿಲ್ಲಿ, ನೆರಳಿನಲ್ಲಿ ನೀರು ಕುಡಿಯಿರಿ. ಇದಕ್ಕೆ {n} ನಿಮಿಷ ಬೇಕು.",
            AlertAction.STOP_AND_SHADE to "ಈಗಲೇ ನಿಲ್ಲಿ, ಹತ್ತಿರದ ನೆರಳಿಗೆ ಹೋಗಿ. ಇದಕ್ಕೆ {n} ನಿಮಿಷ ಬೇಕು.",
        ),
        Lang.TA to mapOf(
            AlertAction.DRINK to "இப்போது தண்ணீர் குடியுங்கள். இதற்கு {n} நிமிடம் ஆகும்.",
            AlertAction.SHADE_AND_DRINK to "நிறுத்தி, நிழலில் தண்ணீர் குடியுங்கள். இதற்கு {n} நிமிடங்கள் ஆகும்.",
            AlertAction.STOP_AND_SHADE to "இப்போதே நிறுத்தி, அருகிலுள்ள நிழலுக்குச் செல்லுங்கள். இதற்கு {n} நிமிடங்கள் ஆகும்.",
        ),
        Lang.TE to mapOf(
            AlertAction.DRINK to "ఇప్పుడు నీళ్ళు తాగండి. దీనికి {n} నిమిషం పడుతుంది.",
            AlertAction.SHADE_AND_DRINK to "ఆగి, నీడలో నీళ్ళు తాగండి. దీనికి {n} నిమిషాలు పడుతుంది.",
            AlertAction.STOP_AND_SHADE to "ఇప్పుడే ఆగి, దగ్గరలోని నీడకు వెళ్ళండి. దీనికి {n} నిమిషాలు పడుతుంది.",
        ),
    )

    private val areYouOk = mapOf(
        Lang.EN to "Are you okay? Say haan.",
        Lang.HI to "क्या आप ठीक हैं? हाँ बोलिए।",
        Lang.KN to "ನೀವು ಚೆನ್ನಾಗಿದ್ದೀರಾ? ಹಾಂ ಎನ್ನಿ.",
        Lang.TA to "நீங்கள் நலமா? ஹாம் என்று சொல்லுங்கள்.",
        Lang.TE to "మీరు బాగున్నారా? హాం అని చెప్పండి.",
    )

    fun collapsePrompt(lang: Lang): String = areYouOk.getValue(lang)

    fun actionFor(level: Level): AlertAction? = when (level) {
        Level.COOL -> null
        Level.WARMING -> AlertAction.DRINK
        Level.STRAINED -> AlertAction.SHADE_AND_DRINK
        Level.DANGER -> AlertAction.STOP_AND_SHADE
    }

    /** [minutesOverride] lets Chhaya Route state the real time cost of the shade or water stop it found. */
    fun plan(level: Level, lang: Lang, minutesOverride: Int? = null): AlertPlan {
        val action = actionFor(level)
        val speech = action?.let { a -> lines.getValue(lang).getValue(a).replace("{n}", (minutesOverride ?: a.minutes).toString()) }
        return when (level) {
            Level.COOL -> AlertPlan(level, null, null, lang, longArrayOf(), AlertScreen.NONE, null, false, false, true, 0)
            Level.WARMING -> AlertPlan(level, action, speech, lang, longArrayOf(0, 200), AlertScreen.AMBER_RING, speech, false, false, true, 10)
            Level.STRAINED -> AlertPlan(level, action, speech, lang, longArrayOf(0, 300, 150, 300), AlertScreen.ORANGE_BANNER, speech, false, false, true, 5)
            Level.DANGER -> AlertPlan(level, action, speech, lang, longArrayOf(0, 500, 150, 500, 150, 500), AlertScreen.RED_TAKEOVER, speech, true, true, false, 0)
        }
    }

    /** Smart snooze: allowed for Warming and Strained for a short while, never at Danger. */
    fun mayStayQuiet(plan: AlertPlan, minutesSinceSnooze: Int): Boolean = plan.snoozeAllowed && minutesSinceSnooze < plan.snoozeMinutes
}
