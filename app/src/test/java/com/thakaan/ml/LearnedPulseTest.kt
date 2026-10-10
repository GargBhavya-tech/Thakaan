package com.thakaan.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

class LearnedPulseTest {
    @Test fun aCleanWaveGivesItsRate() {
        val fps = 30.0
        val w = DoubleArray(900) { sin(2 * PI * 1.2 * it / fps) }
        val r = LearnedPulseRunner.fromWave(w, fps, 45)
        assertNotNull(r.bpm); assertEquals(72.0, r.bpm!!, 1.5)
    }

    @Test fun noiseGivesNoPulse() {
        val rnd = Random(2)
        val r = LearnedPulseRunner.fromWave(DoubleArray(900) { rnd.nextGaussian() }, 30.0, 45)
        assertNull(r.bpm)
    }

    @Test fun tooLittleDataGivesNoPulse() {
        assertNull(LearnedPulseRunner.fromWave(DoubleArray(60), 30.0, 3).bpm)
    }
}
