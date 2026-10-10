package com.thakaan.ml

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.exp
import kotlin.math.ln

/**
 * Line-of-sight classifier for one GNSS satellite (E12): a small XGBoost tree ensemble, evaluated here in plain Kotlin from
 * the JSON dump written by tools/models/train_models.py. Features, in order: C/N0, change in C/N0, elevation, Doppler
 * residual, L1 minus L5 C/N0 gap. Trained on simulated satellites, so it runs in SHADOW MODE: shown in Diagnostics next to
 * the rule in exposure/SkyView.kt, never deciding shade (unknown shade is treated as sun).
 */
class LosClassifier private constructor(private val trees: List<Node>, private val baseMargin: Double) {
    private class Node(val feature: Int, val threshold: Double, val yes: Node?, val no: Node?, val missing: Node?, val leaf: Double?)

    /** Probability that the satellite is in line of sight. */
    fun probability(cn0: Double, deltaCn0: Double, elevationDeg: Double, dopplerResidual: Double, l1l5Gap: Double): Double =
        probability(doubleArrayOf(cn0, deltaCn0, elevationDeg, dopplerResidual, l1l5Gap))

    fun probability(x: DoubleArray): Double {
        var margin = baseMargin
        for (t in trees) margin += walk(t, x)
        return 1.0 / (1.0 + exp(-margin))
    }

    private fun walk(n: Node, x: DoubleArray): Double {
        var cur = n
        while (cur.leaf == null) {
            val v = x[cur.feature]
            cur = if (v.isNaN()) cur.missing!! else if (v < cur.threshold) cur.yes!! else cur.no!!
        }
        return cur.leaf!!
    }

    val treeCount: Int get() = trees.size

    companion object {
        val FEATURES = listOf("cn0", "delta_cn0", "elevation", "doppler_residual", "l1_l5_gap")

        fun fromJson(text: String): LosClassifier {
            val root = JSONObject(text)
            val names = root.getJSONArray("features").let { a -> (0 until a.length()).map { a.getString(it) } }
            require(names == FEATURES) { "unexpected feature order" }
            val trees = root.getJSONArray("trees").let { a -> (0 until a.length()).map { parse(a.getJSONObject(it)) } }
            // base_score 0.5 is a margin of 0; a different base score is converted with the logit
            val p = root.optDouble("base_score", 0.5)
            return LosClassifier(trees, ln(p / (1 - p)))
        }

        private fun parse(o: JSONObject): Node {
            if (o.has("leaf")) return Node(-1, 0.0, null, null, null, o.getDouble("leaf"))
            val children: JSONArray = o.getJSONArray("children")
            val byId = (0 until children.length()).associate { children.getJSONObject(it).getInt("nodeid") to children.getJSONObject(it) }
            val yes = parse(byId.getValue(o.getInt("yes")))
            val no = parse(byId.getValue(o.getInt("no")))
            val missing = if (o.getInt("missing") == o.getInt("yes")) yes else no
            return Node(o.getString("split").removePrefix("f").toInt(), o.getDouble("split_condition"), yes, no, missing, null)
        }
    }
}
