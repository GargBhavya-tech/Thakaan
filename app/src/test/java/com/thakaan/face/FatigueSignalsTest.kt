package com.thakaan.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class FatigueSignalsTest {
    private val fs = 30.0

    /**
     * A synthetic minute of a face. [blinkEverySec] adds 150 ms blinks; [closures] are (startSec, durationSec) long closures;
     * [yawns] and [talk] are jaw openings; [openLevel]/[closedLevel] are this person's raw blink scores.
     */
    private fun gen(
        seconds: Double = 60.0, blinkEverySec: Double = 4.0, closures: List<Pair<Double, Double>> = emptyList(),
        yawns: List<Pair<Double, Double>> = emptyList(), talk: Boolean = false, openLevel: Double = 0.08, closedLevel: Double = 0.85,
        halfClosed: Double? = null, faceLostFrom: Double? = null, faceLostTo: Double? = null, gazeWobble: Double = 0.02, seed: Long = 3,
    ): List<FatigueFrame> {
        val rnd = Random(seed)
        val out = ArrayList<FatigueFrame>()
        val n = (seconds * fs).toInt()
        for (k in 0 until n) {
            val t = k / fs
            var closed = false
            if (blinkEverySec > 0) { val ph = t % blinkEverySec; if (ph in 1.0..1.15) closed = true }
            for ((s, d) in closures) if (t >= s && t < s + d) closed = true
            var jaw = 0.05
            for ((s, d) in yawns) if (t >= s && t < s + d) jaw = 0.9
            if (talk && (t * 4).toInt() % 2 == 0) jaw = 0.7 // opens and closes four times a second, never held
            val raw = when {
                closed -> closedLevel
                halfClosed != null -> halfClosed
                else -> openLevel
            } + 0.01 * rnd.nextGaussian()
            val lost = faceLostFrom != null && faceLostTo != null && t >= faceLostFrom && t < faceLostTo
            out += FatigueFrame((t * 1e9).toLong(), raw, jaw, gazeWobble * rnd.nextGaussian(), gazeWobble * rnd.nextGaussian(), !lost)
        }
        return out
    }

    @Test fun countsBlinksAndNothingElseOnAnAlertFace() {
        val s = FatigueSignals.analyse(gen())
        assertTrue(s.valid)
        assertEquals(15.0, s.blinksPerMin, 1.5) // one every 4 s
        assertEquals(0.0375, s.perclos, 0.015) // 150 ms closed every 4 s
        assertEquals(0, s.longClosures); assertEquals(0, s.yawns)
    }

    @Test fun perclosRisesWithLongerClosures() {
        val alert = FatigueSignals.analyse(gen())
        val drowsy = FatigueSignals.analyse(gen(closures = listOf(10.0 to 2.0, 30.0 to 2.5, 50.0 to 2.0)))
        assertTrue("drowsy ${drowsy.perclos} alert ${alert.perclos}", drowsy.perclos > alert.perclos + 0.08)
        assertEquals(3, drowsy.longClosures)
    }

    @Test fun aSingleLongClosureIsNotCountedAsABlink() {
        val s = FatigueSignals.analyse(gen(blinkEverySec = 0.0, closures = listOf(20.0 to 1.2)))
        assertEquals(1, s.longClosures); assertEquals(0.0, s.blinksPerMin, 0.0)
    }

    @Test fun yawnNeedsASustainedWideJaw() {
        val s = FatigueSignals.analyse(gen(yawns = listOf(15.0 to 3.0, 40.0 to 2.0)))
        assertEquals(2, s.yawns)
        val short = FatigueSignals.analyse(gen(yawns = listOf(15.0 to 0.8)))
        assertEquals("a 0.8 s mouth opening is not a yawn", 0, short.yawns)
    }

    @Test fun talkingIsNotYawning() {
        assertEquals(0, FatigueSignals.analyse(gen(talk = true)).yawns)
    }

    @Test fun squintingInBrightLightIsNotTiredness() {
        val s = FatigueSignals.analyse(gen(halfClosed = 0.45))
        assertFalse(s.valid); assertEquals(FatigueReason.SQUINTING, s.reason)
    }

    @Test fun shortAndFaceLostWindowsGiveNoJudgement() {
        assertEquals(FatigueReason.TOO_SHORT, FatigueSignals.analyse(gen(seconds = 15.0)).reason)
        val s = FatigueSignals.analyse(gen(faceLostFrom = 10.0, faceLostTo = 50.0))
        assertFalse(s.valid); assertEquals(FatigueReason.NO_FACE, s.reason)
    }

    @Test fun aClosureIsNeverCountedAcrossAFaceLostGap() {
        // eyes closed, the face disappears, then returns with eyes closed: two separate stretches, not one long closure
        val frames = gen(blinkEverySec = 0.0, closures = listOf(20.0 to 1.0, 21.5 to 1.0), faceLostFrom = 21.0, faceLostTo = 21.5)
        val s = FatigueSignals.analyse(frames)
        assertTrue(s.valid)
        assertEquals("each closure is about 1 s on its own and none is merged into 2.5 s", 2, s.longClosures)
    }

    @Test fun personalCalibrationFitsAPersonWithHeavyLids() {
        // this person's open-eye score sits at 0.35 and fully closed at 0.62: default levels see them as permanently half-closed
        val heavy = gen(openLevel = 0.35, closedLevel = 0.62)
        val withDefault = FatigueSignals.analyse(heavy)
        assertFalse("default calibration mis-reads this person", withDefault.valid)
        val cal = FatigueCalibration.fromSamples(DoubleArray(heavy.size) { heavy[it].eyeClosure })
        assertNotNull(cal)
        val s = FatigueSignals.analyse(heavy, cal!!)
        assertTrue(s.valid)
        assertEquals(15.0, s.blinksPerMin, 2.0)
    }

    @Test fun calibrationRefusesAFlatSignal() {
        assertNull(FatigueCalibration.fromSamples(DoubleArray(300) { 0.1 }))
        assertNull(FatigueCalibration.fromSamples(DoubleArray(10) { it / 10.0 }))
    }

    @Test fun gazeDriftIsLargerWhenTheGazeWanders() {
        val steady = FatigueSignals.analyse(gen(gazeWobble = 0.02))
        val wandering = FatigueSignals.analyse(gen(gazeWobble = 0.3))
        assertTrue(wandering.gazeDrift > 5 * steady.gazeDrift)
    }
}
