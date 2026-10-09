package com.thakaan.ppg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PpgPipelineTest {
    @Test fun heartRateWithinTwoBpmAcrossTheRange() {
        for (hr in listOf(55.0, 65.0, 75.0, 95.0, 120.0, 150.0, 170.0)) {
            val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = hr, durationSec = 30.0))
            val a = PpgPipeline.analyse(gen.frames)
            // Truth is the rate actually generated over the window (HRV moves the realised mean a little off nominal).
            val inside = gen.beatTimesSec.filter { it <= 30.0 }
            val truth = (inside.size - 1) / (inside.last() - inside.first()) * 60.0
            assertNotNull(a.hrBpm)
            assertTrue("hr=$hr truth=$truth spectral=${a.hrBpm}", abs(a.hrBpm!! - truth) <= 2.0)
            assertNotNull(a.peakHrBpm)
            assertTrue("hr=$hr truth=$truth peaks=${a.peakHrBpm}", abs(a.peakHrBpm!! - truth) <= 5.0)
        }
    }

    @Test fun dicroticNotchIsNotDoubleCounted() {
        for (hr in listOf(55.0, 60.0, 80.0)) {
            val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = hr, durationSec = 40.0))
            val a = PpgPipeline.analyse(gen.frames)
            val expected = hr * 40.0 / 60.0
            assertTrue("hr=$hr peaks=${a.peakIdx.size} expected≈$expected", abs(a.peakIdx.size - expected) <= 3)
        }
    }

    @Test fun surviveModerateNoiseAndTimingJitter() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = 88.0, noiseFrac = 0.25, timingJitterMs = 6.0, seed = 4))
        val a = PpgPipeline.analyse(gen.frames)
        assertTrue("spectral=${a.hrBpm}", abs(a.hrBpm!! - 88.0) <= 3.0)
    }

    @Test fun trackingFollowsARisingHeartRate() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = 80.0, endHrBpm = 110.0, durationSec = 40.0, seed = 8))
        val a = PpgPipeline.analyse(gen.frames)
        val first = a.hrTrack.first().bpm
        val last = a.hrTrack.last().bpm
        assertTrue("first=$first", abs(first - 82.0) <= 5.0)
        assertTrue("last=$last", abs(last - 108.0) <= 5.0)
        // smooth: no window-to-window jump bigger than 8 bpm
        for (i in 1 until a.hrTrack.size) assertTrue("jump at $i", abs(a.hrTrack[i].bpm - a.hrTrack[i - 1].bpm) <= 8.0)
    }

    @Test fun beatTimingGivesAccurateIntervals() {
        val gen = SyntheticPpg.generate(SyntheticPpg.Spec(hrBpm = 70.0, durationSec = 60.0, hrvSdMs = 30.0, seed = 12))
        val a = PpgPipeline.analyse(gen.frames)
        val truthMean = gen.ibiMs.take(a.ibiMs.size).average()
        assertEquals(truthMean, a.ibiMs.average(), 25.0)
    }
}
