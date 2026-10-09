package com.thakaan.risk

/** Result of one on-phone safety scenario. */
class SafetyResult(val id: Int, val name: String, val passed: Boolean, val detail: String)

/**
 * The five scenarios behind the Safety checks tab. Each runs a fresh [RiskEngine] with the given [Guards]; flipping the
 * guard that scenario protects OFF must turn it red, which is what makes "5/5 PASS" mean something on stage.
 */
object SafetyChecks {
    private const val MIN = 60_000_000_000L

    private fun hotExposure(sunNow: Boolean? = true, wbgt: Double? = 32.0) =
        ExposureInputs(wbgt, 90.0, 40.0, 14.0, 0, sunNow)

    private fun fresh(psi: Double?, age: Double = 60.0) = BodyInputs(hrAgeSec = age, hrQuality = 0.9, psi = psi)

    fun runAll(g: Guards = Guards()): List<SafetyResult> = listOf(noFreshHr(g), staleHr(g), gnssLost(g), brainFamilies(g), llmIgnored(g))

    /** 1. A body reading showed high strain, then heart readings stop: the level must hold, not drift down. */
    fun noFreshHr(g: Guards): SafetyResult {
        val e = RiskEngine(g)
        var t = 0L
        repeat(5) { e.update(RiskInputs(t, hotExposure(wbgt = 29.0), fresh(psi = 6.5))); t += MIN }
        val atReading = e.level
        var lowest = e.level
        repeat(40) { e.update(RiskInputs(t, hotExposure(wbgt = 29.0), BodyInputs(hrAgeSec = 3000.0 + it * 60))); lowest = minOf(lowest, e.level); t += MIN }
        val ok = atReading >= Level.STRAINED && lowest >= atReading
        return SafetyResult(1, "No fresh heart rate", ok, "level $atReading with data, lowest ${lowest} after it stopped")
    }

    /** 2. The last heart reading is old (and looked fine) in severe heat: it cannot reassure. */
    fun staleHr(g: Guards): SafetyResult {
        val e = RiskEngine(g)
        var t = 0L
        e.update(RiskInputs(t, hotExposure(wbgt = 33.0), fresh(psi = 0.5))); t += MIN
        var level = e.level
        repeat(30) { level = e.update(RiskInputs(t, hotExposure(wbgt = 33.0), BodyInputs(hrAgeSec = 2400.0, hrQuality = 0.9, psi = 0.5))).level; t += MIN }
        return SafetyResult(2, "Stale heart rate", level >= Level.STRAINED, "stale reading in WBGT 33 gives $level")
    }

    /** 3. GNSS lost / light blocked: unknown shade must be treated exactly like sun. */
    fun gnssLost(g: Guards): SafetyResult {
        val body = fresh(psi = 2.0)
        fun lvl(sun: Boolean?): Pair<Level, Int> {
            val e = RiskEngine(g)
            var o: RiskOutput? = null
            repeat(10) { o = e.update(RiskInputs(it * MIN, ExposureInputs(26.0, 20.0, 5.0, 14.0, 0, sun), body)) }
            return o!!.level to o!!.tss
        }
        val sun = lvl(true); val unknown = lvl(null); val shade = lvl(false)
        val ok = unknown.second == sun.second && unknown.second > shade.second
        return SafetyResult(3, "GNSS lost: unknown shade = sun", ok, "sun TSS ${sun.second}, unknown ${unknown.second}, shade ${shade.second}")
    }

    /** 4. The body looks Cool, but two brain families are flagged: at least Strained. */
    fun brainFamilies(g: Guards): SafetyResult {
        val e = RiskEngine(g)
        val o = e.update(
            RiskInputs(0L, ExposureInputs(24.0, 0.0, 0.0, 9.0, 0, false), fresh(psi = 0.2), BrainInputs(setOf(BrainFamily.MOTION, BrainFamily.TOUCH))),
        )
        return SafetyResult(4, "Body Cool, two brain families flagged", o.level >= Level.STRAINED, "level ${o.level}")
    }

    /** 5. The LLM proposes a different level: ignored. */
    fun llmIgnored(g: Guards): SafetyResult {
        val e = RiskEngine(g)
        e.update(RiskInputs(0L, ExposureInputs(30.0, 60.0, 20.0, 14.0, 0, true), fresh(psi = 3.5)))
        val before = e.level
        val afterDown = e.acceptLlmSuggestion(Level.COOL)
        val afterUp = e.acceptLlmSuggestion(Level.DANGER)
        return SafetyResult(5, "LLM proposes another level", before == afterDown && before == afterUp, "engine $before, LLM asked for COOL and DANGER, still $afterUp")
    }
}
