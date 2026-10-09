package com.thakaan.core

import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProvenanceTest {
    @Before fun clean() = SensorHub.reset()

    @Test fun combineNeverMovesTowardReal() {
        assertEquals(Provenance.REAL, Provenance.combine(Provenance.REAL, Provenance.REAL))
        assertEquals(Provenance.REPLAY, Provenance.combine(Provenance.REAL, Provenance.REPLAY))
        assertEquals(Provenance.SIMULATED, Provenance.combine(Provenance.REPLAY, Provenance.SIMULATED, Provenance.REAL))
    }

    @Test fun combineOfNothingIsNotReal() {
        assertEquals(Provenance.SIMULATED, Provenance.combine())
    }

    @Test fun liveIsStampedReal() {
        SensorHub.publishLive(Stream.ACCEL, 1_000L, floatArrayOf(0f, 0f, 9.8f))
        SensorHub.snapshot()
        val s = SensorHub.stats.value.getValue(Stream.ACCEL)
        assertEquals(Provenance.REAL, s.lastProvenance)
        assertFalse(s.sawNonReal)
    }

    @Test fun replayIsStampedReplayAndStaysFlagged() {
        SensorHub.publishLive(Stream.LIGHT, 1_000L, floatArrayOf(100f))
        SensorHub.publishReplay(Stream.LIGHT, 2_000L, floatArrayOf(100f))
        SensorHub.publishLive(Stream.LIGHT, 3_000L, floatArrayOf(100f))
        SensorHub.snapshot()
        val s = SensorHub.stats.value.getValue(Stream.LIGHT)
        assertTrue("a stream that ever carried non-REAL data must stay flagged", s.sawNonReal)
        assertEquals(3L, s.count)
    }
}
