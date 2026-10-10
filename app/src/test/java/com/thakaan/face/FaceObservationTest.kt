package com.thakaan.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceObservationTest {
    @Test fun eyeClosureAndJawComeFromTheNamedScores() {
        val s = mapOf("eyeBlinkLeft" to 0.8f, "eyeBlinkRight" to 0.6f, "jawOpen" to 0.7f)
        assertTrue(Blendshapes.usable(s))
        assertEquals(0.7, Blendshapes.eyeClosure(s), 1e-6)
        assertEquals(0.7, Blendshapes.jawOpen(s), 1e-6)
    }

    @Test fun missingScoresAreNoInformationNotOpenEyes() {
        assertFalse(Blendshapes.usable(mapOf("jawOpen" to 0.1f)))
        assertFalse(Blendshapes.usable(emptyMap()))
        val obs = FaceObservation(0L, FaceBox(0.3, 0.3, 0.7, 0.7), 150.0, 100.0, 90.0, hasBlend = false, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertFalse("a frame with no expression scores must not count as an eyes-open frame", obs.toFatigueFrame().faceFound)
        assertTrue(obs.toFaceFrame().faceFound) // the colour is still usable for the pulse
    }

    @Test fun noFaceMeansNoFaceForBoth() {
        val obs = FaceObservation(0L, null, 0.0, 0.0, 0.0, hasBlend = true, 0.0, 0.0, 0.0, 0.0, 0.0)
        assertFalse(obs.toFaceFrame().faceFound); assertFalse(obs.toFatigueFrame().faceFound)
    }

    @Test fun gazeSignsFollowTheWearersLeftAndUp() {
        val left = mapOf("eyeLookOutLeft" to 0.7f, "eyeLookInRight" to 0.7f)
        assertTrue(Blendshapes.gazeX(left) > 0.6)
        val right = mapOf("eyeLookInLeft" to 0.7f, "eyeLookOutRight" to 0.7f)
        assertTrue(Blendshapes.gazeX(right) < -0.6)
        assertTrue(Blendshapes.gazeY(mapOf("eyeLookUpLeft" to 0.5f, "eyeLookUpRight" to 0.5f)) > 0.4)
        assertTrue(Blendshapes.gazeY(mapOf("eyeLookDownLeft" to 0.5f, "eyeLookDownRight" to 0.5f)) < -0.4)
    }

    private val lm: (Int) -> Pair<Double, Double> = { i ->
        when (i) {
            SkinRegions.TOP -> 0.50 to 0.15
            SkinRegions.GLABELLA -> 0.50 to 0.38
            SkinRegions.BROW_L -> 0.40 to 0.36
            SkinRegions.BROW_R -> 0.60 to 0.36
            SkinRegions.EDGE_L -> 0.25 to 0.55
            SkinRegions.EDGE_R -> 0.75 to 0.55
            SkinRegions.CHEEK_L -> 0.35 to 0.60
            SkinRegions.CHEEK_R -> 0.65 to 0.60
            else -> 0.5 to 0.5
        }
    }

    @Test fun skinRegionsAreForeheadAndTwoCheeksInsideThePicture() {
        val r = SkinRegions.regions(lm, 320, 240)
        assertEquals(3, r.size)
        r.forEach { assertTrue(it.left >= 0 && it.top >= 0 && it.right <= 320 && it.bottom <= 240 && it.area > 100) }
        val forehead = r[0]
        assertTrue("forehead is above the brows (y 0.36 * 240 = 86)", forehead.bottom <= (0.36 * 240).toInt() + 1)
        assertTrue("the cheek rectangles are on opposite sides of the face centre", r[1].right <= 160 && r[2].left >= 160)
    }

    @Test fun tinyOrBrokenRegionsAreDropped() {
        val collapsed: (Int) -> Pair<Double, Double> = { 0.5 to 0.5 }
        assertTrue(SkinRegions.regions(collapsed, 320, 240).isEmpty())
    }
}
