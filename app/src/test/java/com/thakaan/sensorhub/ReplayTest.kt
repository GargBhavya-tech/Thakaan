package com.thakaan.sensorhub

import com.thakaan.core.Provenance
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.DataOutputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ReplayTest {
    @Before fun clean() = SensorHub.reset()

    private fun writeSession(f: File, n: Int): List<Triple<Stream, Long, FloatArray>> {
        val rows = (0 until n).map { i ->
            val s = if (i % 2 == 0) Stream.ACCEL else Stream.LIGHT
            Triple(s, 1_000_000_000L + i * 10_000_000L, floatArrayOf(i.toFloat(), i * 0.5f, 9.8f))
        }
        DataOutputStream(f.outputStream().buffered()).use { d ->
            d.writeInt(SessionFile.MAGIC); d.writeInt(SessionFile.VERSION)
            rows.forEach { (s, t, v) ->
                d.writeByte(s.ordinal); d.writeLong(t); d.writeByte(v.size); v.forEach { d.writeFloat(it) }
            }
        }
        return rows
    }

    @Test fun replayIsStampedReplayAndMatchesTheRecording() = runTest {
        val f = File.createTempFile("thk", ".bin").apply { deleteOnExit() }
        val rows = writeSession(f, 200)

        val got = ArrayList<Reading>()
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) { SensorHub.readings.collect { got.add(it) } }

        Replayer { testScheduler.currentTime }.play(f, speed = 20f)
        collector.cancel()

        assertEquals(rows.size, got.size)
        got.forEachIndexed { i, r ->
            assertEquals(Provenance.REPLAY, r.provenance)
            assertEquals(rows[i].first, r.stream)
            assertEquals(rows[i].second, r.tNanos)
            assertArrayEquals(rows[i].third, r.values, 0f)
        }
        SensorHub.snapshot()
        assertTrue(SensorHub.stats.value.values.all { it.lastProvenance == Provenance.REPLAY && it.sawNonReal })
    }

    @Test fun twentyTimesIsTwentyTimesFaster() = runTest {
        val f = File.createTempFile("thk", ".bin").apply { deleteOnExit() }
        writeSession(f, 201) // 2000 ms of recorded time
        Replayer { testScheduler.currentTime }.play(f, speed = 20f)
        assertEquals(100L, testScheduler.currentTime) // 2000 / 20
    }

    @Test fun recorderOnlyKeepsRealAndRoundTrips() = runTest {
        val f = File.createTempFile("thk", ".bin").apply { deleteOnExit() }
        val rec = StreamRecorder(f, UnconfinedTestDispatcher(testScheduler))
        rec.start(this)
        SensorHub.publishLive(Stream.GYRO, 5L, floatArrayOf(1f, 2f, 3f))
        SensorHub.publishReplay(Stream.GYRO, 6L, floatArrayOf(9f, 9f, 9f)) // must not be recorded
        SensorHub.publishLive(Stream.GYRO, 7L, floatArrayOf(4f, 5f, 6f))
        testScheduler.runCurrent()
        rec.stop()
        val back = SessionFile.readAll(f)
        assertEquals(listOf(5L, 7L), back.map { it.tNanos })
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), back[1].values, 0f)
    }
}
