package com.thakaan.settings

import android.content.Context
import org.json.JSONObject

/**
 * The daily 3-tap check-in (#28): slept, ate, unwell. Each answer may be left unknown, and unknown is never read as a
 * benign cause. It stays on the phone (invariant 7). Only "slept poorly" feeds the brain-battery context today; "ate" and
 * "unwell" are recorded for later use and do not change any level (they can never lower one).
 */
class DailyCheckIn(val date: String, val sleptWell: Boolean?, val ate: Boolean?, val unwell: Boolean?) {
    /** The value the engine expects: true only when the worker said they slept badly; unknown stays unknown. */
    val sleptPoorly: Boolean? get() = sleptWell?.not()

    fun toJson(): String {
        val o = JSONObject().put("date", date)
        sleptWell?.let { o.put("slept", it) }; ate?.let { o.put("ate", it) }; unwell?.let { o.put("unwell", it) }
        return o.toString()
    }

    companion object {
        fun fromJson(s: String): DailyCheckIn? = try {
            val o = JSONObject(s)
            val d = o.getString("date")
            if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(d)) null
            else DailyCheckIn(d, o.opt("slept") as? Boolean, o.opt("ate") as? Boolean, o.opt("unwell") as? Boolean)
        } catch (_: Exception) { null }
    }
}

interface CheckInStore {
    fun load(): DailyCheckIn?
    fun save(c: DailyCheckIn)
}

/** The screen is shown once per calendar day: until an answer (or an explicit skip) is stored for today's date. */
fun checkInDue(store: CheckInStore, today: String): Boolean = store.load()?.date != today

class PrefsCheckInStore(ctx: Context) : CheckInStore {
    private val prefs = ctx.getSharedPreferences("thakaan_checkin", Context.MODE_PRIVATE)
    override fun load(): DailyCheckIn? = prefs.getString("last", null)?.let { DailyCheckIn.fromJson(it) }
    override fun save(c: DailyCheckIn) { prefs.edit().putString("last", c.toJson()).apply() }
}
