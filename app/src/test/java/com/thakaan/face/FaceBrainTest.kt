package com.thakaan.face

import com.thakaan.brain.BrainBattery
import com.thakaan.brain.BrainContext
import com.thakaan.brain.BrainResult
import com.thakaan.brain.BrainTest
import com.thakaan.risk.BrainFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FaceBrainTest {
    private val SEC = 1_000_000_000L

    private fun window(perclos: Double, yawnsPerMin: Double = 0.0, valid: Boolean = true) =
        FatigueSummary(valid, if (valid) null else FatigueReason.SQUINTING, 60.0, 15.0, perclos, 0, 0, yawnsPerMin, 0.02)

    @Test fun invalidWindowGivesNoResultsAndIsNeverReadAsFine() {
        assertTrue(FatigueSignals.toBrainResults(window(0.5, valid = false), 0L, 8.0, "iqoo15").isEmpty())
    }

    @Test fun validWindowGivesTwoFaceResultsInTheFaceFamilyAndMountedPlacement() {
        val r = FatigueSignals.toBrainResults(window(0.05, 0.4), 0L, 8.0, "iqoo15")
        assertEquals(setOf(BrainTest.FACE_PERCLOS, BrainTest.FACE_YAWN_RATE), r.map { it.test }.toSet())
        assertTrue(r.all { it.test.family == BrainFamily.FACE && it.placement == "mounted" })
    }

    @Test fun drowsyWindowsFlagTheFaceFamilyOnlyAgainstTheWorkersOwnBaselineAndOnlyAfterSeveral() {
        val rng = Random(5)
        val b = BrainBattery()
        repeat(8) { b.addBaseline(BrainResult(BrainTest.FACE_PERCLOS, 0.04 + rng.nextGaussian() * 0.01, it * SEC, 8.0, "iqoo15", "mounted")) }
        val ctx = BrainContext(exposureLoad = 0.8)
        // one drowsy window alone does not alarm
        FatigueSignals.toBrainResults(window(0.16), 100 * SEC, 8.5, "iqoo15").forEach { b.ingest(it, ctx) }
        assertTrue(b.flaggedFamilies().isEmpty())
        repeat(3) { i -> FatigueSignals.toBrainResults(window(0.04), (200 + i) * SEC, 8.5, "iqoo15").forEach { b.ingest(it, ctx) } }
        repeat(6) { i -> FatigueSignals.toBrainResults(window(0.16), (300 + i) * SEC, 8.5, "iqoo15").forEach { b.ingest(it, ctx) } }
        assertEquals(setOf(BrainFamily.FACE), b.flaggedFamilies())
    }

    @Test fun withoutABaselineThereIsNoJudgement() {
        val b = BrainBattery()
        repeat(10) { i -> FatigueSignals.toBrainResults(window(0.6), i * SEC, 8.0, "iqoo15").forEach { b.ingest(it, BrainContext()) } }
        assertTrue(b.flaggedFamilies().isEmpty())
    }
}
