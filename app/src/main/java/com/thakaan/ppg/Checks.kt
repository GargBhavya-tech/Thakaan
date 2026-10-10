package com.thakaan.ppg

import com.thakaan.core.Provenance
import kotlin.math.abs
import kotlin.math.sqrt

enum class CheckKind(val settleSec: Double, val captureSec: Double) {
    /** 10 s settle (discarded) + 25 s of data (enough windows to check the rate stays steady): a heart rate in about 35 s. */
    QUICK(10.0, 25.0),

    /** 10 s settle (discarded) + 60 s of data: HR, RMSSD, perfusion, pulse amplitude, respiration in about 70 s. */
    FULL(10.0, 60.0),
}

/** Where and when the check happened, so a reading is only ever compared with one taken in the same state (ticket #14). */
class CheckContext(
    val epochMs: Long,
    val postureState: String,
    val secSinceStop: Int?,
    val secSincePostureChange: Int?,
    /** Hot conditions make pulse-rate variability read high (vasodilation); never present it as good recovery. */
    val hot: Boolean,
    val provenance: Provenance,
)

class CheckResult(
    val kind: CheckKind,
    val accepted: Boolean,
    val reasons: List<Reason>,
    val prompt: String?,
    /** Present only when [accepted]: a bad capture never produces a number. */
    val hrBpm: Double?,
    val rmssdMs: Double?,
    val rmssdHeatAffected: Boolean,
    val perfusionIndexPct: Double?,
    val pulseAmplitude: Double?,
    val respirationBpm: Double?,
    val captureSeconds: Double,
    val context: CheckContext,
)

object Checks {
    private fun rejected(kind: CheckKind, reasons: List<Reason>, secs: Double, ctx: CheckContext) = CheckResult(
        kind, false, reasons, reasons.minByOrNull { it.ordinal }?.prompt, null, null, false, null, null, null, secs, ctx,
    )

    fun evaluate(kind: CheckKind, frames: List<PpgFrame>, ctx: CheckContext): CheckResult {
        if (frames.size < 30) return rejected(kind, listOf(Reason.TOO_SHORT), 0.0, ctx)
        val t0 = frames.first().tNanos
        val settled = frames.filter { (it.tNanos - t0) / 1e9 >= kind.settleSec }
        if (settled.size < 30) return rejected(kind, listOf(Reason.TOO_SHORT), 0.0, ctx)
        val tCap = settled.first().tNanos
        val capture = settled.filter { (it.tNanos - tCap) / 1e9 <= kind.captureSec + 0.5 }
        val secs = (capture.last().tNanos - tCap) / 1e9
        val needed = when (kind) { CheckKind.QUICK -> kind.captureSec - 1.0; CheckKind.FULL -> kind.captureSec - 1.0 }
        if (secs < needed) return rejected(kind, listOf(Reason.TOO_SHORT), secs, ctx)

        val analysis = PpgPipeline.analyse(capture)
        val q = Quality.evaluate(analysis, Thresholds(minSeconds = needed))

        // Two independent ways to accept: the strict red-only beat test, or the colour-combining test that must stay
        // consistent over time (FingertipPos). Both need the basics: finger present, not saturated, no dropped frames.
        val pos = FingertipPos.analyse(capture)
        val basicsOk = q.reasons.none { it == Reason.NO_FINGER || it == Reason.SATURATED || it == Reason.DROPPED_FRAMES || it == Reason.TOO_SHORT }
        val posOk = pos?.accepted == true && basicsOk
        val oldOk = q.accepted
        if (!oldOk && !posOk) {
            // when the colour test ran and found the reading unsteady, say that, rather than a misleading "wipe the lens"
            val reasons = if (pos != null && basicsOk) listOf(Reason.UNSTEADY) else q.reasons
            return rejected(kind, reasons, secs, ctx)
        }
        // both accepted but far apart: neither can be trusted
        if (oldOk && posOk && abs(analysis.hrBpm!! - pos!!.hrBpm!!) > 8.0) return rejected(kind, listOf(Reason.HR_DISAGREE), secs, ctx)

        val hr = if (posOk) pos!!.hrBpm!! else analysis.hrBpm!!
        if (kind == CheckKind.QUICK) {
            return CheckResult(kind, true, emptyList(), null, hr, null, false, q.perfusionIndexPct, Dsp.robustRange(analysis.filtered), null, secs, ctx)
        }
        // RMSSD only from a full minute of clean data (never from under 60 s).
        val rmssd = if (secs >= 59.0 && oldOk) rmssd(analysis.ibiMs) else null
        val resp = if (secs >= 40.0 && oldOk) respirationBpm(analysis) else null
        return CheckResult(
            kind, true, emptyList(), null, hr, rmssd, rmssd != null && ctx.hot, q.perfusionIndexPct,
            Dsp.robustRange(analysis.filtered), resp, secs, ctx,
        )
    }

    /** Root mean square of successive differences, ignoring pairs that look like missed or extra beats (>20% jump). */
    fun rmssd(ibiMs: DoubleArray): Double? {
        if (ibiMs.size < 20) return null
        var sum = 0.0
        var n = 0
        for (i in 1 until ibiMs.size) {
            val d = ibiMs[i] - ibiMs[i - 1]
            if (kotlin.math.abs(d) > 0.2 * ibiMs[i - 1]) continue
            sum += d * d
            n++
        }
        return if (n < 15) null else sqrt(sum / n)
    }

    /** Breaths per minute from how the pulse amplitude rises and falls with breathing (0.1 to 0.5 Hz). */
    fun respirationBpm(a: PpgAnalysis): Double? {
        val p = a.peakIdx
        if (p.size < 20) return null
        val ts = DoubleArray(p.size) { p[it] / a.fs }
        val amps = DoubleArray(p.size) { a.filtered[p[it].toInt().coerceIn(0, a.filtered.size - 1)] }
        val (_, env) = runCatching { Dsp.resampleUniform(ts, amps, 4.0) }.getOrNull() ?: return null
        val freqs = DoubleArray(41) { 0.1 + 0.01 * it }
        val power = Dsp.powerAt(env, 4.0, freqs)
        val best = power.indices.maxByOrNull { power[it] } ?: return null
        return freqs[best] * 60.0
    }
}
