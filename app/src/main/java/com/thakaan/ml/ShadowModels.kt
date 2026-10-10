package com.thakaan.ml

import android.content.Context
import com.thakaan.motion.Activity
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What one model reported for Diagnostics: where it is, and whether it ran. */
class ModelLine(val name: String, val status: String, val detail: String)

/**
 * The four small models trained on simulated data (tools/models/train_models.py), run with the LiteRT CPU interpreter. They
 * are SHADOW models: their output is shown next to the rule-based result and never decides a level. The exception is
 * [raiseForecast], which can only raise a warning (invariant 3). A missing model file just means the slot is empty.
 */
class ShadowModels(private val ctx: Context) {
    private val interpreters = HashMap<String, Interpreter?>()
    @Volatile private var los: LosClassifier? = null

    private fun interp(file: String): Interpreter? = interpreters.getOrPut(file) {
        try {
            val bytes = ctx.assets.open("models/$file").use { it.readBytes() }
            Interpreter(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() })
        } catch (_: Throwable) { null }
    }

    private fun run(file: String, input: FloatArray, outCount: Int): FloatArray? {
        val it = interp(file) ?: return null
        val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder()).apply { input.forEach { v -> putFloat(v) }; rewind() }
        val outBuf = ByteBuffer.allocateDirect(outCount * 4).order(ByteOrder.nativeOrder())
        return try { it.run(inBuf, outBuf); outBuf.rewind(); FloatArray(outCount) { outBuf.getFloat() } } catch (_: Throwable) { null }
    }

    /** Activity probabilities (IDLE, RIDING, WALKING, STAIRS, LIFTING) from 2 s of accelerometer at 50 Hz; null if unavailable. */
    fun activity(ax: DoubleArray, ay: DoubleArray, az: DoubleArray): Pair<Activity, Double>? {
        if (ax.size != ACT_N) return null
        val mx = ax.average(); val my = ay.average(); val mz = az.average()
        val inp = FloatArray(ACT_N * 3) { i -> val k = i / 3; (when (i % 3) { 0 -> ax[k] - mx; 1 -> ay[k] - my; else -> az[k] - mz }).toFloat() }
        val p = run("activity_cnn.tflite", inp, 5) ?: return null
        val best = p.indices.maxByOrNull { p[it] } ?: return null
        return Activity.entries[best] to p[best].toDouble()
    }

    /** Cleaned chest signal for 5 s at 100 Hz (already normalised); null if unavailable. */
    fun denoiseScg(x: DoubleArray): DoubleArray? {
        if (x.size != ScgHeart.N) return null
        val out = run("scg_denoiser.tflite", FloatArray(x.size) { x[it].toFloat() }, ScgHeart.N) ?: return null
        return DoubleArray(out.size) { out[it].toDouble() }
    }

    /** TSS at +15 and +30 min from the last 20 minutes of (TSS, sun share); null if unavailable. */
    fun gruForecast(tss: DoubleArray, sunShare: DoubleArray): Pair<Int, Int>? {
        if (tss.size != 20 || sunShare.size != 20) return null
        val inp = FloatArray(40) { i -> if (i % 2 == 0) (tss[i / 2] / 100.0).toFloat() else sunShare[i / 2].toFloat() }
        val o = run("strain_gru.tflite", inp, 2) ?: return null
        return (o[0] * 100).toInt().coerceIn(0, 100) to (o[1] * 100).toInt().coerceIn(0, 100)
    }

    /** Line-of-sight probability for one satellite; null when the tree file is absent. */
    fun lineOfSight(cn0: Double, deltaCn0: Double, elevationDeg: Double, dopplerResidual: Double, l1l5Gap: Double): Double? {
        val c = los ?: try {
            LosClassifier.fromJson(ctx.assets.open("models/los_xgboost.json").use { it.readBytes().decodeToString() }).also { los = it }
        } catch (_: Throwable) { return null }
        return c.probability(cn0, deltaCn0, elevationDeg, dopplerResidual, l1l5Gap)
    }

    /**
     * Runs each model once on a synthetic input of the right shape and reports whether it loaded and produced numbers. This
     * proves the files are present and executable on the phone; it does not prove accuracy on real data.
     */
    fun selfTest(): List<ModelLine> {
        val z = DoubleArray(ACT_N)
        val act = activity(DoubleArray(ACT_N) { 0.02 * (it % 3) }, z, z)
        val scg = denoiseScg(DoubleArray(ScgHeart.N) { Math.sin(it * 0.3) })
        val gru = gruForecast(DoubleArray(20) { 40.0 + it }, DoubleArray(20) { 0.7 })
        val l = lineOfSight(45.0, 0.0, 60.0, 0.1, 0.5)
        fun line(name: String, ok: Boolean, detail: String) = ModelLine(name, if (ok) "runs" else "missing or failed", detail)
        return listOf(
            line("Activity CNN", act != null, act?.let { "idle window read as ${it.first}" } ?: "-"),
            line("Chest-vibration denoiser", scg != null, scg?.let { "${it.size} samples out" } ?: "-"),
            line("Strain GRU", gru != null, gru?.let { "+15 min ${it.first}, +30 min ${it.second}" } ?: "-"),
            line("Line-of-sight trees", l != null, l?.let { "open-sky satellite: %.0f%% in view".format(it * 100) } ?: "-"),
            LearnedPulseRunner(ctx).let { r -> val ok = r.available; r.close(); line("Face pulse (EfficientPhys)", ok, if (ok) "loads" else "-") },
            ShadeRunner(ctx).let { r -> val ok = r.available; r.close(); line("Shade segmentation (SegFormer)", ok, if (ok) "loads" else "-") },
        )
    }

    fun close() { interpreters.values.forEach { try { it?.close() } catch (_: Throwable) {} }; interpreters.clear() }

    companion object {
        const val ACT_N = 100

        /**
         * Combines the rule-based forecast with the model's: each horizon takes the HIGHER of the two, so the model can
         * only bring a warning forward, never push it away.
         */
        fun raiseForecast(rule: com.thakaan.risk.Forecast, model15: Int?, model30: Int?): com.thakaan.risk.Forecast {
            val a = maxOf(rule.tss15, model15 ?: 0); val b = maxOf(rule.tss30, model30 ?: 0)
            return com.thakaan.risk.Forecast(
                a, minOf(rule.tss15Low, a), maxOf(rule.tss15High, a), b, minOf(rule.tss30Low, b), maxOf(rule.tss30High, b),
                rule.minutesToStrained, rule.minutesToDanger,
            )
        }
    }
}
