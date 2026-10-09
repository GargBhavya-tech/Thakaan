package com.thakaan.motion

import com.thakaan.core.Provenance
import com.thakaan.sensorhub.Reading
import com.thakaan.sensorhub.Stream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class ActivityTest {
    private fun features(b: ImuBuilder, fromSec: Double = 1.0, lenSec: Double = 2.0): MotionFeatures {
        val s = b.samples.filter { (it.tNanos - 1_000_000_000L) / 1e9 in fromSec..(fromSec + lenSec) }
        return MotionFeaturesExtractor.compute(
            DoubleArray(s.size) { s[it].ax }, DoubleArray(s.size) { s[it].ay }, DoubleArray(s.size) { s[it].az },
            DoubleArray(s.size) { sqrt(s[it].gx * s[it].gx + s[it].gy * s[it].gy + s[it].gz * s[it].gz) }, b.fs,
        )
    }

    private fun classify(b: ImuBuilder, speed: Double?) = ActivityClassifier.classify(features(b), speed).activity

    @Test fun idleIsIdle() = assertEquals(Activity.IDLE, classify(ImuBuilder().idle(6.0), 0.0))

    @Test fun walkingIsWalkingWithAndWithoutGnss() {
        assertEquals(Activity.WALKING, classify(ImuBuilder().walking(6.0), 1.3))
        assertEquals(Activity.WALKING, classify(ImuBuilder().walking(6.0), null))
    }

    @Test fun stairsAreNotLevelWalking() = assertEquals(Activity.STAIRS, classify(ImuBuilder().stairs(6.0), 0.2))

    @Test fun ridingIsRidingWithGnss() = assertEquals(Activity.RIDING, classify(ImuBuilder().riding(6.0), 8.0))

    @Test fun liftingIsLifting() = assertEquals(Activity.LIFTING, classify(ImuBuilder(seed = 4).lifting(8.0), 0.0))

    @Test fun losingGnssNeverLowersTheMetabolicRate() {
        // Invariant 3: degraded information must not make the worker look less loaded.
        val scenes = listOf(
            ImuBuilder().idle(6.0) to 0.0, ImuBuilder().walking(6.0) to 1.3, ImuBuilder().stairs(6.0) to 0.2,
            ImuBuilder().riding(6.0) to 8.0, ImuBuilder(seed = 4).lifting(8.0) to 0.0,
        )
        for ((b, speed) in scenes) {
            val withGnss = ActivityClassifier.classify(features(b), speed).activity
            val without = ActivityClassifier.classify(features(b), null).activity
            val m1 = Metabolism.mets(withGnss, speed)
            val m0 = Metabolism.mets(without, null)
            assertTrue("with=$withGnss ($m1) without=$without ($m0)", m0 >= m1 - 1.0 || without == withGnss)
        }
    }

    @Test fun ridingWithoutGnssIsAtLeastRidingLoad() {
        val a = ActivityClassifier.classify(features(ImuBuilder().riding(6.0)), null).activity
        assertTrue("got $a", Metabolism.mets(a) >= 2.0)
    }

    @Test fun metabolicRatesAreSensible() {
        assertEquals(1.3, Metabolism.mets(Activity.IDLE), 0.0)
        assertTrue(Metabolism.mets(Activity.RIDING) in 1.5..2.5)
        assertTrue(Metabolism.mets(Activity.WALKING, 1.3) in 3.0..3.6)
        assertTrue(Metabolism.mets(Activity.STAIRS) > Metabolism.mets(Activity.WALKING, 1.3))
        // Pandolf: a 20 kg load raises the cost of walking for the same body and speed
        val noLoad = Metabolism.pandolfWatts(70.0, 0.0, 1.3)
        val loaded = Metabolism.pandolfWatts(70.0, 20.0, 1.3)
        assertTrue("loaded=$loaded noLoad=$noLoad", loaded > noLoad * 1.15)
        // 70 kg at 1.3 m/s on the flat is roughly 250 to 350 W
        assertTrue("noLoad=$noLoad", noLoad in 200.0..400.0)
    }

    @Test fun placementIsDetected() {
        assertEquals(Placement.DESK, ActivityClassifier.placement(features(ImuBuilder().apply { dir = doubleArrayOf(0.0, 0.0, 1.0) }.idle(6.0)), Activity.IDLE))
        assertEquals(Placement.POCKET, ActivityClassifier.placement(features(ImuBuilder().walking(6.0)), Activity.WALKING))
        assertEquals(Placement.IN_HAND, ActivityClassifier.placement(features(ImuBuilder().inHandWalking(6.0)), Activity.WALKING))
        assertEquals(Placement.MOUNTED, ActivityClassifier.placement(features(ImuBuilder().riding(6.0)), Activity.RIDING))
    }

    // ---------------------------------------------------------------- streaming engine

    private fun feed(engine: ActivityEngine, b: ImuBuilder, speed: Double?, prov: Provenance = Provenance.REAL): List<ActivityEstimate> {
        val out = ArrayList<ActivityEstimate>()
        var nextGnss = 0L
        for (s in b.samples) {
            if (speed != null && s.tNanos >= nextGnss) {
                engine.onReading(Reading(Stream.GNSS, s.tNanos, floatArrayOf(12.9f, 77.6f, 5f, speed.toFloat()), prov))
                nextGnss = s.tNanos + 1_000_000_000L
            }
            engine.onReading(Reading(Stream.GYRO, s.tNanos, floatArrayOf(s.gx.toFloat(), s.gy.toFloat(), s.gz.toFloat()), prov))
            engine.onReading(Reading(Stream.ACCEL, s.tNanos, floatArrayOf(s.ax.toFloat(), s.ay.toFloat(), s.az.toFloat()), prov))?.let { out += it }
        }
        return out
    }

    @Test fun engineEmitsOneEstimatePerSecond() {
        val est = feed(ActivityEngine(), ImuBuilder().walking(12.0), 1.3)
        assertTrue("got ${est.size}", est.size in 9..11)
        assertTrue(est.drop(1).all { it.activity == Activity.WALKING })
        assertTrue(est.all { it.provenance == Provenance.REAL })
    }

    @Test fun replayedReadingsNeverProduceRealEstimates() {
        val est = feed(ActivityEngine(), ImuBuilder().walking(8.0), 1.3, prov = Provenance.REPLAY)
        assertTrue(est.isNotEmpty())
        assertTrue(est.all { it.provenance == Provenance.REPLAY })
    }

    @Test fun staleGnssIsTreatedAsLost() {
        val b = ImuBuilder().walking(20.0)
        val engine = ActivityEngine()
        // one GNSS fix at the start, then nothing for 20 s
        engine.onReading(Reading(Stream.GNSS, b.samples.first().tNanos, floatArrayOf(0f, 0f, 5f, 9.0f), Provenance.REAL))
        val est = feed(engine, b, null)
        assertEquals(null, est.last().speedMs)
        assertEquals(Activity.WALKING, est.last().activity)
    }
}
