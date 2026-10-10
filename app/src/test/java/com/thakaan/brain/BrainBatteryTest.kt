package com.thakaan.brain

import com.thakaan.risk.BrainFamily
import com.thakaan.risk.Symptom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

class BrainBatteryTest {
    private val SEC = 1_000_000_000L

    // ------------------------------------------------------------------ CUSUM engine (#36)

    @Test fun oneBadTestDoesNotAlarmButFiveMildlySlowOnesDo() {
        val c = CusumTracker()
        assertFalse(c.update(6.0)) // wild outlier is capped
        c.reset()
        val states = (1..5).map { c.update(1.5) }
        assertEquals(listOf(false, false, false, false, true), states)
    }

    @Test fun alarmClearsOnlyThroughNormalResults() {
        val c = CusumTracker()
        repeat(5) { c.update(1.5) }
        assertTrue(c.alarmed)
        c.update(0.4) // slightly under k -> small decay, still alarmed
        assertTrue(c.alarmed)
        repeat(10) { c.update(-1.0) }
        assertFalse(c.alarmed)
    }

    private fun baseline(b: BrainBattery, test: BrainTest, rng: Random, mean: Double, sd: Double, device: String = "iqoo15", placement: String = "", hour: Double = 8.0, n: Int = 8) =
        repeat(n) { b.addBaseline(BrainResult(test, mean + rng.nextGaussian() * sd, it * SEC, hour, device, placement)) }

    @Test fun slightlySlowerTouchTestsEventuallyFlagTheTouchFamily() {
        val rng = Random(3)
        val b = BrainBattery()
        baseline(b, BrainTest.FITTS_THROUGHPUT, rng, 5.0, 0.3)
        val ctx = BrainContext(exposureLoad = 0.8)
        // one slow result: no flag
        b.ingest(BrainResult(BrainTest.FITTS_THROUGHPUT, 4.2, 100 * SEC, 8.5, "iqoo15"), ctx)
        assertTrue(b.flaggedFamilies().isEmpty())
        // normal results bring it back, then five mildly slow ones (about 2 sigma slower) flag it
        repeat(3) { b.ingest(BrainResult(BrainTest.FITTS_THROUGHPUT, 5.0, (200 + it) * SEC, 8.5, "iqoo15"), ctx) }
        repeat(5) { b.ingest(BrainResult(BrainTest.FITTS_THROUGHPUT, 4.4, (300 + it) * SEC, 8.5, "iqoo15"), ctx) }
        assertEquals(setOf(BrainFamily.TOUCH), b.flaggedFamilies())
    }

    @Test fun noBaselineMeansNoJudgementAndNeverACalmingSignal() {
        val b = BrainBattery()
        repeat(10) { b.ingest(BrainResult(BrainTest.TREMOR, 99.0, it * SEC, 8.0, "iqoo15"), BrainContext()) }
        assertTrue("no baseline -> nothing flagged (the other safety inputs still apply)", b.flaggedTests().isEmpty())
        assertEquals(0.0, b.cusumState(BrainTest.TREMOR, "iqoo15"), 0.0)
    }

    @Test fun baselinesAreNeverMixedAcrossPhonesOrPlacements() {
        val rng = Random(4)
        val b = BrainBattery()
        baseline(b, BrainTest.GAIT_STRIDE_CV, rng, 0.03, 0.004, placement = "POCKET")
        // same numbers on the same phone but in hand have no baseline, so they are not scored
        repeat(8) { b.ingest(BrainResult(BrainTest.GAIT_STRIDE_CV, 0.2, it * SEC, 8.0, "iqoo15", "IN_HAND"), BrainContext()) }
        assertTrue(b.flaggedTests().isEmpty())
        // a different phone also has no baseline
        repeat(8) { b.ingest(BrainResult(BrainTest.GAIT_STRIDE_CV, 0.2, it * SEC, 8.0, "other", "POCKET"), BrainContext()) }
        assertTrue(b.flaggedTests().isEmpty())
        // the right phone and placement does flag
        repeat(8) { b.ingest(BrainResult(BrainTest.GAIT_STRIDE_CV, 0.2, it * SEC, 8.0, "iqoo15", "POCKET"), BrainContext(exposureLoad = 0.8)) }
        assertTrue(b.flaggedTests().contains(BrainTest.GAIT_STRIDE_CV))
    }

    @Test fun aTiredCoolMorningIsDownWeightedButHeavyExposureNeverIs() {
        fun alarmsAfter(ctx: BrainContext): Int {
            val rng = Random(5)
            val b = BrainBattery()
            baseline(b, BrainTest.FITTS_THROUGHPUT, rng, 5.0, 0.3)
            for (i in 1..30) {
                b.ingest(BrainResult(BrainTest.FITTS_THROUGHPUT, 4.1, i * SEC, 8.5, "iqoo15"), ctx)
                if (b.flaggedTests().isNotEmpty()) return i
            }
            return 99
        }
        val tiredCool = alarmsAfter(BrainContext(sleepPoorLastNight = true, exposureLoad = 0.1))
        val neutral = alarmsAfter(BrainContext(exposureLoad = 0.1))
        val unknownAnswers = alarmsAfter(BrainContext(sleepPoorLastNight = null, exposureLoad = 0.1))
        val tiredHot = alarmsAfter(BrainContext(sleepPoorLastNight = true, exposureLoad = 0.9))
        assertTrue("tired cool is slower to alarm ($tiredCool vs $neutral)", tiredCool > neutral)
        assertEquals("unanswered questions are neutral", neutral, unknownAnswers)
        assertTrue("heavy exposure is not down-weighted by sleep ($tiredHot vs $neutral)", tiredHot <= neutral)
    }

    @Test fun twoFamiliesCanBeFlaggedAtOnce() {
        val rng = Random(6)
        val b = BrainBattery()
        baseline(b, BrainTest.FITTS_THROUGHPUT, rng, 5.0, 0.3)
        baseline(b, BrainTest.TREMOR, rng, 0.010, 0.001)
        val ctx = BrainContext(exposureLoad = 0.9)
        repeat(8) {
            b.ingest(BrainResult(BrainTest.FITTS_THROUGHPUT, 4.0, it * SEC, 8.5, "iqoo15"), ctx)
            b.ingest(BrainResult(BrainTest.TREMOR, 0.016, it * SEC, 8.5, "iqoo15"), ctx)
        }
        assertEquals(setOf(BrainFamily.TOUCH, BrainFamily.MOTION), b.flaggedFamilies())
    }

    @Test fun confoundedTremorIsStoredButNotScored() {
        val rng = Random(7)
        val b = BrainBattery()
        baseline(b, BrainTest.TREMOR, rng, 0.010, 0.001)
        repeat(10) { b.ingest(BrainResult(BrainTest.TREMOR, 0.05, it * SEC, 8.5, "iqoo15", "", setOf("lifting_confound")), BrainContext()) }
        assertTrue(b.flaggedTests().isEmpty())
        assertTrue(BrainTest.TREMOR in b.skippedForConfound())
    }

    // ------------------------------------------------------------------ tremor (#32)

    private fun holdStill(fs: Double, sec: Double, tremorAmp: Double, hz: Double = 8.0, seed: Long = 1): Triple<DoubleArray, DoubleArray, DoubleArray> {
        val rng = Random(seed)
        val n = (fs * sec).toInt()
        val ax = DoubleArray(n) { rng.nextGaussian() * 0.01 }
        val ay = DoubleArray(n) { rng.nextGaussian() * 0.01 }
        val az = DoubleArray(n) { 9.81 + tremorAmp * sin(2 * PI * hz * it / fs) + rng.nextGaussian() * 0.01 }
        return Triple(ax, ay, az)
    }

    @Test fun tremorPowerRisesClearlyWhenTheHandShakesMore() {
        val (a, b, c) = holdStill(100.0, 12.0, 0.01)
        val base = Tremor.analyse(a, b, c, 100.0, null, 0)
        val (a2, b2, c2) = holdStill(100.0, 12.0, 0.04)
        val shaky = Tremor.analyse(a2, b2, c2, 100.0, null, 0)
        assertTrue(base.valid && shaky.valid)
        assertTrue("power ${base.bandPower} -> ${shaky.bandPower}", shaky.bandPower > 8 * base.bandPower)
        assertTrue(shaky.bandShare > base.bandShare)
    }

    @Test fun tremorBandIgnoresSlowSway() {
        val (a, b, c) = holdStill(100.0, 12.0, 0.2, hz = 1.0)
        val slow = Tremor.analyse(a, b, c, 100.0, null, 0)
        val (a2, b2, c2) = holdStill(100.0, 12.0, 0.2, hz = 8.0)
        val fast = Tremor.analyse(a2, b2, c2, 100.0, null, 0)
        assertTrue(fast.bandPower > 50 * slow.bandPower)
    }

    @Test fun tremorTestsNearLiftingAreFlaggedAndShortOrSlowCapturesRejected() {
        val (a, b, c) = holdStill(100.0, 12.0, 0.01)
        val near = Tremor.analyse(a, b, c, 100.0, lastLiftingNanos = 100 * SEC, nowNanos = 130 * SEC)
        assertTrue(near.liftingConfound)
        assertTrue(Tremor.toResult(near, 130 * SEC, 9.0, "d")!!.flags.contains("lifting_confound"))
        assertFalse(Tremor.analyse(a, b, c, 100.0, 100 * SEC, 200 * SEC).liftingConfound)
        val (s1, s2, s3) = holdStill(100.0, 5.0, 0.01)
        assertFalse(Tremor.analyse(s1, s2, s3, 100.0, null, 0).valid)
        val (l1, l2, l3) = holdStill(40.0, 12.0, 0.01)
        assertFalse(Tremor.analyse(l1, l2, l3, 40.0, null, 0).valid)
        assertNull(Tremor.toResult(Tremor.analyse(s1, s2, s3, 100.0, null, 0), 0, 9.0, "d"))
    }

    // ------------------------------------------------------------------ Fitts (#33)

    private fun tapRun(seed: Long, mtSec: Double, sigmaPx: Double, missEvery: Int = 0): List<Tap> {
        val rng = Random(seed)
        val out = ArrayList<Tap>()
        var t = 0L
        for (i in 0 until 40) {
            val target = i % 2
            val tx = if (target == 0) 200.0 else 800.0
            t += ((mtSec + rng.nextGaussian() * 0.04) * 1e9).toLong()
            val missed = missEvery > 0 && i % missEvery == 0
            out += Tap(t, tx + rng.nextGaussian() * sigmaPx + if (missed) 120.0 else 0.0, 500.0 + rng.nextGaussian() * sigmaPx, target)
        }
        return out
    }
    private val targets = listOf(TargetSpec(200.0, 500.0, 120.0), TargetSpec(800.0, 500.0, 120.0))

    @Test fun fiveBaselineRunsGiveAStableThroughput() {
        val tp = (1L..5L).map { Fitts.analyse(tapRun(it, 0.42, 7.0), targets).throughputBitsPerSec }
        val mean = tp.average()
        val cv = sqrt(tp.sumOf { (it - mean) * (it - mean) } / 4) / mean
        assertTrue("cv=$cv tp=$tp", cv < 0.12)
    }

    @Test fun nonDominantHandIsClearlyWorse() {
        val good = Fitts.analyse(tapRun(1, 0.42, 7.0), targets)
        val bad = Fitts.analyse(tapRun(1, 0.62, 15.0, missEvery = 6), targets)
        assertTrue("${good.throughputBitsPerSec} vs ${bad.throughputBitsPerSec}", bad.throughputBitsPerSec < 0.7 * good.throughputBitsPerSec)
        assertTrue(bad.errorRate > good.errorRate)
    }

    @Test fun fittsRejectsTooFewTapsAndStoresPerDevice() {
        assertFalse(Fitts.analyse(tapRun(1, 0.4, 7.0).take(5), targets).valid)
        val r = Fitts.analyse(tapRun(1, 0.4, 7.0), targets)
        val stored = Fitts.toResults(r, 0, 9.0, "iqoo15")
        assertEquals(2, stored.size); assertTrue(stored.all { it.deviceId == "iqoo15" })
    }

    // ------------------------------------------------------------------ gait (#37)

    private fun walk(seed: Long, stepJitter: Double, swayAmp: Double, fs: Double = 50.0, sec: Double = 40.0): Pair<DoubleArray, DoubleArray> {
        val rng = Random(seed)
        val n = (fs * sec).toInt()
        val vert = DoubleArray(n) { rng.nextGaussian() * 0.15 }
        val lat = DoubleArray(n) { Math.abs(rng.nextGaussian()) * swayAmp }
        var t = 0.5
        while (t < sec - 0.5) {
            val c = (t * fs).toInt()
            for (k in -4..4) if (c + k in 0 until n) vert[c + k] += 3.0 * exp(-(k * k) / 4.0)
            t += 0.55 * (1.0 + rng.nextGaussian() * stepJitter)
        }
        return vert to lat
    }

    @Test fun aWobblyWalkScoresClearlyHigherInTheSamePlacement() {
        val (v1, l1) = walk(1, 0.02, 0.3)
        val (v2, l2) = walk(2, 0.18, 0.9)
        val steady = Gait.analyse(v1, l1, 50.0)
        val wobble = Gait.analyse(v2, l2, 50.0)
        assertTrue(steady.valid && wobble.valid)
        assertTrue("cv ${steady.strideCv} vs ${wobble.strideCv}", wobble.strideCv > 3 * steady.strideCv)
        assertTrue(wobble.acfPeak < steady.acfPeak)
        assertTrue(wobble.swayRms > steady.swayRms)
    }

    @Test fun gaitIsNotScoredWithoutAKnownPlacementOrWhenStandingStill() {
        val (v, l) = walk(1, 0.02, 0.3)
        val r = Gait.analyse(v, l, 50.0)
        assertTrue(Gait.toResults(r, "UNKNOWN", 0, 9.0, "d").isEmpty())
        assertTrue(Gait.toResults(r, "", 0, 9.0, "d").isEmpty())
        assertEquals(2, Gait.toResults(r, "POCKET", 0, 9.0, "d").size)
        val still = Gait.analyse(DoubleArray(2000) { 0.01 * sin(it * 0.3) }, DoubleArray(2000), 50.0)
        assertFalse(still.valid)
    }

    // ------------------------------------------------------------------ PVT-B (#38)

    @Test fun pvtCountsLapsesFalseStartsAndSpeed() {
        val rng = Random(2)
        val good = (1..40).map { PvtTrial(2000, 280.0 + rng.nextGaussian() * 25) }
        val tired = (1..40).map { i -> if (i % 4 == 0) PvtTrial(2000, 650.0) else if (i % 9 == 0) PvtTrial(2000, null) else PvtTrial(2000, 380.0 + rng.nextGaussian() * 40) } + listOf(PvtTrial(1500, 60.0))
        val g = PvtB.score(good); val t = PvtB.score(tired)
        assertEquals(0, g.lapses)
        assertTrue(t.lapses >= 10 && t.falseStarts == 1)
        assertTrue("speed ${g.meanResponseSpeed} vs ${t.meanResponseSpeed}", t.meanResponseSpeed < 0.8 * g.meanResponseSpeed)
        assertFalse(PvtB.score(good.take(10)).valid)
    }

    @Test fun pvtScheduleIsRandomButBounded() {
        val s = PvtB.schedule(1)
        assertTrue(s.all { it in 1000L..4000L })
        assertTrue(s.toSet().size > 10)
        assertTrue(s.sum() >= 100_000)
    }

    // ------------------------------------------------------------------ speech code (#34)

    private fun say(vararg w: String, gap: Double = 0.25, len: Double = 0.45): List<SpokenWord> {
        var t = 0.2
        return w.map { x -> SpokenWord(x, t, t + len).also { t += len + gap } }
    }

    @Test fun correctCodeInEnglishHindiAndKannadaPasses() {
        val code = "4170"
        assertTrue(SpeechCode.check(code, say("four", "one", "seven", "zero"), 25.0).correct)
        assertTrue(SpeechCode.check(code, say("char", "ek", "saat", "shunya"), 25.0).correct)
        assertTrue(SpeechCode.check(code, say("ನಾಲ್ಕು", "ಒಂದು", "ಏಳು", "ಸೊನ್ನೆ"), 25.0).correct)
        assertTrue(SpeechCode.check(code, listOf(SpokenWord("4170", 0.2, 2.0)), 25.0).correct)
    }

    @Test fun wrongDigitsAreCountedAndFlagged() {
        val r = SpeechCode.check("4170", say("four", "one", "three", "zero"), 25.0)
        assertFalse(r.correct); assertEquals(1, r.wrongDigits)
        assertEquals(4, SpeechCode.check("4170", say("nine", "nine", "nine", "nine"), 25.0).wrongDigits)
        assertFalse(SpeechCode.check("4170", say("four", "one"), 25.0).correct)
    }

    @Test fun slurredSlowSpeechHasLowerRateAndLongPauses() {
        val fast = SpeechCode.check("4170", say("four", "one", "seven", "zero", len = 0.35, gap = 0.1), 25.0)
        val slow = SpeechCode.check("4170", say("four", "one", "seven", "zero", len = 0.9, gap = 1.1), 25.0)
        assertTrue(slow.articulationRate < fast.articulationRate)
        assertTrue(slow.longPauses >= 3)
    }

    @Test fun veryNoisyAudioIsNotJudgedAndVoiceQualityNeedsAQuietRoom() {
        val noisy = SpeechCode.check("4170", say("four", "one", "seven", "zero"), 3.0)
        assertFalse(noisy.valid)
        assertTrue(SpeechCode.toResults(noisy, 0, 9.0, "d").isEmpty())
        assertFalse(SpeechCode.check("4170", say("four", "one", "seven", "zero"), 12.0).voiceQualityAllowed)
        assertTrue(SpeechCode.check("4170", say("four", "one", "seven", "zero"), 28.0).voiceQualityAllowed)
        assertFalse(SpeechCode.check("4170", emptyList(), 28.0).correct)
    }

    @Test fun codesAre4DigitsAndVary() {
        val rng = Random(1)
        val codes = (1..50).map { SpeechCode.newCode(rng) }
        assertTrue(codes.all { it.length == 4 && it.all(Char::isDigit) })
        assertTrue(codes.toSet().size > 30)
    }

    // ------------------------------------------------------------------ symptoms (#35)

    @Test fun symptomsAreStoredAndSevereOnesNeverExpireBySilence() {
        val log = SymptomLog()
        assertTrue(log.active(0).isEmpty())
        log.record(0, setOf(Symptom.THIRST, Symptom.CONFUSION), 7, "check")
        assertEquals(setOf(Symptom.THIRST, Symptom.CONFUSION), log.active(10 * 60 * SEC))
        // an hour later with no new answer: thirst expires, confusion is still held
        assertEquals(setOf(Symptom.CONFUSION), log.active(90 * 60 * SEC))
        // a newer answer without it clears it
        log.record(100 * 60 * SEC, setOf(Symptom.THIRST), 5, "level_change")
        assertEquals(setOf(Symptom.THIRST), log.active(101 * 60 * SEC))
        assertEquals(5, log.lastEffort())
    }

    @Test fun explicitNoneClearsAndEffortIsRangeChecked() {
        val log = SymptomLog()
        log.record(0, setOf(Symptom.DIZZINESS), null, "check")
        log.record(60 * SEC, emptySet(), 3, "check")
        assertTrue(log.active(61 * SEC).isEmpty())
        var threw = false
        try { log.record(0, emptySet(), 11, "x") } catch (_: IllegalArgumentException) { threw = true }
        assertTrue(threw)
        assertNotNull(log.size())
    }
}
