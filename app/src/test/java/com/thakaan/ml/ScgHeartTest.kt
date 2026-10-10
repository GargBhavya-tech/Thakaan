package com.thakaan.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class ScgHeartTest {
    private fun clean(bpm: Double, jitter: Double = 0.0): DoubleArray {
        val rnd = Random(3)
        val x = DoubleArray(500)
        var t = 0.3
        while (t < 5.0) {
            for (i in x.indices) { val k = i / 100.0 - t; if (k > 0 && k < 0.25) x[i] += exp(-k / 0.04) * sin(2 * PI * 17 * k) }
            t += 60.0 / bpm + rnd.nextGaussian() * jitter
        }
        return x
    }

    @Test fun readsTheRateOfACleanTrain() {
        for (bpm in listOf(55.0, 75.0, 110.0)) {
            val r = ScgHeart.beats(clean(bpm)); assertNotNull("bpm $bpm: ${r.reason}", r.bpm); assertEquals(bpm, r.bpm!!, 4.0)
        }
    }

    @Test fun noiseAloneGivesNoRate() {
        val rnd = Random(5); val n = DoubleArray(500) { rnd.nextGaussian() }
        assertNull(ScgHeart.beats(n).bpm)
    }

    @Test fun irregularBeatsAreRejected() {
        assertNull(ScgHeart.beats(clean(80.0, jitter = 0.25)).bpm)
    }

    @Test fun normaliseGivesZeroMean() {
        val x = ScgHeart.normalise(DoubleArray(100) { it * 3.0 + 7 })
        assertEquals(0.0, x.average(), 1e-9)
    }
}
