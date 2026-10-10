package com.thakaan.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Runs the fingertip method on REAL captures recorded with the PPG lab on the iQOO 15. The files are a person's own pulse
 * traces, so they are never committed: point PPG_FIXTURES at a folder holding them (cap1.csv hand-held firm, cap2_control.csv
 * no finger, cap3.csv hand-held light, cap4.csv phone on the table with the finger resting) and the test runs; otherwise it
 * is skipped. Expected: only the table capture is accepted, at about 62 bpm, and everything noisy is rejected.
 */
class FingertipPosFixtureTest {
    private fun load(name: String): List<PpgFrame>? {
        val dir = System.getenv("PPG_FIXTURES") ?: return null
        val f = File(dir, name)
        if (!f.exists()) return null
        return f.readLines().drop(1).filter { it.isNotBlank() }.map {
            val c = it.split(',')
            PpgFrame(c[0].toLong() * 1_000_000L, c[1].toDouble(), c[2].toDouble(), c[3].toDouble())
        }
    }

    /** The same slice the Quick check analyses: drop the 10 s settle, keep the next 25 s. */
    private fun quickSlice(frames: List<PpgFrame>): List<PpgFrame> {
        val t0 = frames.first().tNanos
        val settled = frames.filter { (it.tNanos - t0) / 1e9 >= 10.0 }
        val tc = settled.first().tNanos
        return settled.filter { (it.tNanos - tc) / 1e9 <= 25.5 }
    }

    @Test fun theTableCaptureIsAcceptedAtAboutSixtyTwo() {
        val frames = load("cap4.csv"); assumeTrue("no fixtures", frames != null)
        val r = FingertipPos.analyse(quickSlice(frames!!))
        assertNotNull(r)
        assertTrue("reason ${r!!.reason} windows ${r.windowHrs.map { it.toInt() }}", r.accepted)
        assertEquals(62.0, r.hrBpm!!, 3.0)
    }

    @Test fun noisyAndEmptyCapturesAreRejected() {
        for (name in listOf("cap1.csv", "cap2_control.csv", "cap3.csv")) {
            val frames = load(name); assumeTrue("no fixtures", frames != null)
            val r = FingertipPos.analyse(quickSlice(frames!!))
            assertFalse("$name was accepted at ${r?.hrBpm} windows ${r?.windowHrs?.map { it.toInt() }}", r?.accepted == true)
        }
    }
}
