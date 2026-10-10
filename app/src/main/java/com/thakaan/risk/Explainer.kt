package com.thakaan.risk

/** Optional phrasing model (Gemma via LiteRT-LM on the GPU). It may only re-word a line the risk engine already decided. */
fun interface LlmPhraser {
    /** Returns a re-worded line, or null when the model is unavailable or fails. */
    fun phrase(templateLine: String, lang: Lang, level: Level, action: AlertAction): String?
}

class Explanation(val text: String, val fromModel: Boolean, val rejectedBecause: String? = null)

/**
 * The "why" line shown and spoken with an alert (#57). The template line is always built first; the model can only offer a
 * replacement, and the replacement is accepted only if it passes [validate]. The level and the action are never read back
 * from the model's text (invariant 5), so a bad or missing model output changes nothing but wording.
 */
object Explainer {
    private val reasons: Map<Lang, Map<String, String>> = mapOf(
        Lang.EN to mapOf("heat" to "It is very hot and sunny.", "rest" to "You have not rested for a long time.", "body" to "Your body is under strain.", "brain" to "You are reacting slower than usual.", "sick" to "You said you feel unwell."),
        Lang.HI to mapOf("heat" to "बहुत गर्मी और धूप है।", "rest" to "आपने बहुत देर से आराम नहीं किया।", "body" to "आपके शरीर पर ज़ोर पड़ रहा है।", "brain" to "आप सामान्य से धीरे प्रतिक्रिया दे रहे हैं।", "sick" to "आपने बताया कि तबीयत ठीक नहीं है।"),
        Lang.KN to mapOf("heat" to "ತುಂಬಾ ಬಿಸಿ ಮತ್ತು ಬಿಸಿಲು ಇದೆ.", "rest" to "ನೀವು ತುಂಬಾ ಹೊತ್ತು ವಿಶ್ರಾಂತಿ ತೆಗೆದುಕೊಂಡಿಲ್ಲ.", "body" to "ನಿಮ್ಮ ದೇಹದ ಮೇಲೆ ಒತ್ತಡ ಇದೆ.", "brain" to "ನೀವು ಸಾಮಾನ್ಯಕ್ಕಿಂತ ನಿಧಾನವಾಗಿ ಪ್ರತಿಕ್ರಿಯಿಸುತ್ತಿದ್ದೀರಿ.", "sick" to "ನಿಮಗೆ ಹುಷಾರಿಲ್ಲ ಎಂದು ಹೇಳಿದ್ದೀರಿ."),
        Lang.TA to mapOf("heat" to "மிகவும் வெப்பமாகவும் வெயிலாகவும் உள்ளது.", "rest" to "நீங்கள் நீண்ட நேரமாக ஓய்வு எடுக்கவில்லை.", "body" to "உங்கள் உடல் அழுத்தத்தில் உள்ளது.", "brain" to "நீங்கள் வழக்கத்தை விட மெதுவாக பதிலளிக்கிறீர்கள்.", "sick" to "உடல்நிலை சரியில்லை என்று சொன்னீர்கள்."),
        Lang.TE to mapOf("heat" to "చాలా వేడిగా, ఎండగా ఉంది.", "rest" to "మీరు చాలా సేపు విశ్రాంతి తీసుకోలేదు.", "body" to "మీ శరీరం ఒత్తిడిలో ఉంది.", "brain" to "మీరు మామూలు కంటే నెమ్మదిగా స్పందిస్తున్నారు.", "sick" to "మీకు బాగాలేదని చెప్పారు."),
    )

    /** First matching reason category for the worker's most important driver code. */
    fun category(codes: List<String>): String = codes.firstNotNullOfOrNull {
        when {
            it.startsWith("symptom") -> "sick"
            it.startsWith("wbgt") || it == "shade_unknown" || it == "sun_dose" -> "heat"
            it == "rest_debt" -> "rest"
            it.startsWith("brain") -> "brain"
            else -> null
        }
    } ?: "body"

    fun template(plan: AlertPlan, driverCodes: List<String>): String {
        val action = plan.speech ?: return ""
        val why = reasons.getValue(plan.speechLang).getValue(category(driverCodes))
        return "$why $action"
    }

    private val forbidden = listOf(
        "safe", "fine", "no need", "ignore", "don't worry", "not serious", "ok to continue", "keep working", "skip",
        "सुरक्षित", "चिंता न", "कोई बात नहीं", "काम जारी", "नज़रअंदाज़", "ठीक है",
    )

    /** Why a model line is unacceptable, or null if it passes. Deliberately strict: when in doubt the template wins. */
    fun validate(candidate: String, plan: AlertPlan): String? {
        val c = candidate.trim()
        if (c.isEmpty()) return "empty"
        if (c.length > 220) return "too long to speak quickly"
        val lower = c.lowercase()
        forbidden.firstOrNull { lower.contains(it) }?.let { return "reassurance or instruction to continue: $it" }
        if ('°' in c || lower.contains("celsius") || lower.contains("fahrenheit")) return "mentions a body or air temperature"
        if (Regex("\\b(hr|bpm|pulse|heart rate)\\b").containsMatchIn(lower)) return "quotes a physiological number"
        val minutes = plan.action?.minutes?.toString() ?: return "no action to phrase"
        val numbers = Regex("[0-9]+").findAll(c).map { it.value }.toList()
        if (minutes !in numbers) return "does not state the time cost ($minutes)"
        if (numbers.any { it != minutes }) return "adds numbers that were not decided by the risk engine"
        // Wording check in English only; other languages rely on the number and reassurance filters above.
        if (plan.action == AlertAction.DRINK && (lower.contains("stop") || lower.contains("shade"))) return "names a stronger action than the engine chose"
        if (plan.action != AlertAction.STOP_AND_SHADE && lower.contains("emergency")) return "escalates beyond the level"
        return null
    }

    /** The line to show and speak. Falls back to the template whenever the model is absent, returns nothing or fails validation. */
    fun explain(plan: AlertPlan, driverCodes: List<String>, phraser: LlmPhraser?): Explanation {
        val base = template(plan, driverCodes)
        val action = plan.action
        if (phraser == null || action == null || base.isEmpty()) return Explanation(base, false)
        val out = try { phraser.phrase(base, plan.speechLang, plan.level, action) } catch (_: Throwable) { null }
            ?: return Explanation(base, false, "model returned nothing")
        val bad = validate(out, plan)
        return if (bad == null) Explanation(out.trim(), true) else Explanation(base, false, bad)
    }
}
