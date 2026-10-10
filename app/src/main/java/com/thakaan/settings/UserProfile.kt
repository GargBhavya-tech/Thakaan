package com.thakaan.settings

import android.content.Context
import com.thakaan.risk.Lang
import com.thakaan.shift.ShiftConfig
import org.json.JSONObject

/**
 * The worker's profile (#28). It lives only on the phone (invariant 7). Every field is range-checked, and an unknown or
 * missing value falls back to the CAUTIOUS default: not acclimatised (earlier alerts), not "fit" resting heart rate.
 */
class UserProfile(
    val name: String = "",
    val language: Lang = Lang.HI,
    val massKg: Double = 70.0,
    val heightM: Double = 1.70,
    val ageYears: Int = 30,
    /** Days of work in this heat so far; 0 until the worker says otherwise, which keeps the earlier 38.0 °C anchor. */
    val acclimatisationDays: Int = 0,
    val restSeatedHr: Double = 70.0,
    val emergencyContact: String = "",
    val shareLocationWithSupervisor: Boolean = false,
    /** What kind of work: decides which steps the Quick check has. Missing or unknown means the general check. */
    val workType: WorkType = WorkType.OTHER_GIG,
) {
    fun withWorkType(w: WorkType) = UserProfile(name, language, massKg, heightM, ageYears, acclimatisationDays, restSeatedHr, emergencyContact, shareLocationWithSupervisor, w)

    fun toShiftConfig(startEpochSec: Long, deviceId: String = "phone") = ShiftConfig(
        startEpochSec, bodyMassKg = massKg, heightM = heightM, ageYears = ageYears,
        acclimatisationDays = acclimatisationDays, restSeatedHr = restSeatedHr, deviceId = deviceId,
    )

    fun toJson(): String = JSONObject()
        .put("name", name).put("lang", language.name).put("massKg", massKg).put("heightM", heightM).put("age", ageYears)
        .put("accl", acclimatisationDays).put("restHr", restSeatedHr).put("contact", emergencyContact)
        .put("shareLoc", shareLocationWithSupervisor).put("work", workType.name).toString()

    companion object {
        val MASS = 35.0..160.0
        val HEIGHT = 1.20..2.10
        val AGE = 16..75
        val REST_HR = 40.0..110.0

        /** Parse stored JSON; any field that is missing or out of range is replaced by its default, never trusted. */
        fun fromJson(text: String?): UserProfile {
            val d = UserProfile()
            val j = try { JSONObject(text ?: return d) } catch (_: Exception) { return d }
            fun num(k: String, r: ClosedFloatingPointRange<Double>, def: Double) = j.optDouble(k, Double.NaN).takeIf { !it.isNaN() && it in r } ?: def
            val age = j.optInt("age", -1).takeIf { it in AGE } ?: d.ageYears
            val accl = j.optInt("accl", 0).coerceIn(0, 30)
            val lang = runCatching { Lang.valueOf(j.optString("lang", "")) }.getOrDefault(d.language)
            return UserProfile(
                j.optString("name", "").take(40), lang, num("massKg", MASS, d.massKg), num("heightM", HEIGHT, d.heightM), age, accl,
                num("restHr", REST_HR, d.restSeatedHr), j.optString("contact", "").filter { it.isDigit() || it == '+' }.take(15),
                j.optBoolean("shareLoc", false), WorkType.parse(j.optString("work", "")),
            )
        }
    }
}

enum class OnboardingStep { LANGUAGE, WORK, BODY, HEAT_EXPERIENCE, CONTACT, PRIVACY, DONE }

/** What the worker has entered so far plus the validation that decides if the next step may open. */
class OnboardingFlow {
    var step = OnboardingStep.LANGUAGE; private set
    var language = Lang.HI
    var workType = WorkType.OTHER_GIG
    var massKg: Double? = null
    var heightM: Double? = null
    var age: Int? = null
    var acclimatisationDays = 0
    var restHr: Double? = null
    var contact = ""
    var share = false

    /** Null when the current step is valid; otherwise a short message saying what to fix. */
    fun problem(): String? = when (step) {
        OnboardingStep.LANGUAGE, OnboardingStep.WORK -> null
        OnboardingStep.BODY -> when {
            massKg?.let { it in UserProfile.MASS } != true -> "Weight must be between 35 and 160 kg."
            heightM?.let { it in UserProfile.HEIGHT } != true -> "Height must be between 1.20 and 2.10 m."
            age?.let { it in UserProfile.AGE } != true -> "Age must be between 16 and 75."
            else -> null
        }
        OnboardingStep.HEAT_EXPERIENCE -> if (restHr?.let { it !in UserProfile.REST_HR } == true) "Resting pulse looks wrong. Leave it blank if unsure." else null
        OnboardingStep.CONTACT -> if (contact.isNotEmpty() && contact.count { it.isDigit() } < 7) "Phone number looks too short." else null
        OnboardingStep.PRIVACY, OnboardingStep.DONE -> null
    }

    fun next(): Boolean {
        if (problem() != null || step == OnboardingStep.DONE) return false
        step = OnboardingStep.entries[step.ordinal + 1]
        return true
    }

    fun back() { if (step.ordinal > 0 && step != OnboardingStep.DONE) step = OnboardingStep.entries[step.ordinal - 1] }

    /** Only valid once every required step has passed; an unanswered optional field keeps its cautious default. */
    fun build(): UserProfile? {
        if (step != OnboardingStep.DONE) return null
        val d = UserProfile()
        return UserProfile(
            "", language, massKg ?: d.massKg, heightM ?: d.heightM, age ?: d.ageYears, acclimatisationDays,
            restHr ?: d.restSeatedHr, contact, share, workType,
        )
    }
}

interface ProfileStore {
    fun load(): UserProfile?
    fun save(p: UserProfile)
}

class PrefsProfileStore(ctx: Context) : ProfileStore {
    private val prefs = ctx.getSharedPreferences("thakaan_profile", Context.MODE_PRIVATE)
    override fun load(): UserProfile? = prefs.getString("profile", null)?.let { UserProfile.fromJson(it) }
    override fun save(p: UserProfile) { prefs.edit().putString("profile", p.toJson()).apply() }
}
