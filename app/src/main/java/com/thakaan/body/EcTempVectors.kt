package com.thakaan.body

import android.content.Context
import org.json.JSONObject
import kotlin.math.abs

/**
 * Runs the team's reference cases (assets/ectemp_vectors.json) against the Kotlin filter with personalisation OFF.
 * Diagnostics shows "ECTemp vectors N/7". A check that cannot fail proves nothing, so [check] takes a deliberately
 * broken model in tests (see EcTempTest) and must go red.
 */
object EcTempVectors {
    class Case(val name: String, val ct0: Double, val v0: Double, val hrs: List<Double?>, val expected: List<Pair<Double, Double>>)

    class Report(val passed: Int, val total: Int, val failures: List<String>) {
        val summary: String get() = "$passed/$total"
    }

    fun parse(json: String): List<Case> {
        val arr = JSONObject(json).getJSONArray("cases")
        return (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            val hrs = c.getJSONArray("hrs")
            val exp = c.getJSONArray("expected")
            Case(
                name = c.getString("name"),
                ct0 = c.getDouble("ct0"),
                v0 = c.getDouble("v0"),
                hrs = (0 until hrs.length()).map { if (hrs.isNull(it)) null else hrs.getDouble(it) },
                expected = (0 until exp.length()).map { exp.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } },
            )
        }
    }

    fun check(json: String, tol: Double = 1e-9, model: ObservationModel = PublishedQuadratic): Report {
        val cases = parse(json)
        val failures = mutableListOf<String>()
        for (c in cases) {
            var s = EcState(c.ct0, c.v0)
            var worst = 0.0
            var bad = c.hrs.size != c.expected.size
            c.hrs.forEachIndexed { i, hr ->
                s = EcTemp.step(s.ct, s.v, hr, model)
                val (ect, ev) = c.expected.getOrNull(i) ?: return@forEachIndexed
                worst = maxOf(worst, abs(s.ct - ect), abs(s.v - ev))
            }
            if (worst > tol) bad = true
            if (bad) failures += "${c.name} (max error $worst)"
        }
        return Report(cases.size - failures.size, cases.size, failures)
    }

    fun checkAssets(ctx: Context): Report =
        check(ctx.assets.open("ectemp_vectors.json").bufferedReader().use { it.readText() })
}
