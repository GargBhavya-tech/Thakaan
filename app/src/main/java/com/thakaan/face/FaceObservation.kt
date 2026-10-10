package com.thakaan.face

import kotlin.math.abs

/**
 * Everything one camera frame yields, already reduced to numbers (invariant 7: no pixels, no landmark set is kept).
 * [box] is null when no face was tracked; [hasBlend] is false when the landmarker returned no expression scores, in which
 * case the fatigue side gets no information from this frame.
 */
class FaceObservation(
    val tNanos: Long,
    val box: FaceBox?,
    val r: Double, val g: Double, val b: Double,
    val hasBlend: Boolean,
    val eyeClosure: Double, val jawOpen: Double, val gazeX: Double, val gazeY: Double,
    /** Movement of the face centre since the previous frame as a fraction of the face width. */
    val motion: Double,
    /** The skin regions read for the pulse, for drawing on the live screen only. Pixel units of the (half-size) frame. */
    val rois: List<PixelRect> = emptyList(),
    /** Mean (r, g, b) of each skin region separately and of a background patch, for tuning only. */
    val roiMeans: List<DoubleArray> = emptyList(),
    val background: DoubleArray? = null,
) {
    fun toFaceFrame() = FaceFrame(tNanos, r, g, b, box != null, motion, roiMeans)
    fun toFatigueFrame() = FatigueFrame(tNanos, eyeClosure, jawOpen, gazeX, gazeY, box != null && hasBlend)
}

/**
 * Reads the few MediaPipe blendshape scores we use. Only eyelid, jaw and gaze: nothing about emotion, identity, age or
 * appearance is read from the model's output.
 */
object Blendshapes {
    const val BLINK_L = "eyeBlinkLeft"
    const val BLINK_R = "eyeBlinkRight"
    const val JAW_OPEN = "jawOpen"

    /** False when the scores are missing the ones we need, so a bad model output is "no information", not "eyes open". */
    fun usable(s: Map<String, Float>) = BLINK_L in s && BLINK_R in s && JAW_OPEN in s

    fun eyeClosure(s: Map<String, Float>): Double = (v(s, BLINK_L) + v(s, BLINK_R)) / 2.0
    fun jawOpen(s: Map<String, Float>): Double = v(s, JAW_OPEN)

    private fun v(s: Map<String, Float>, k: String): Double = (s[k] ?: 0f).toDouble()

    /** Positive when the person looks to their own left: a left eye looking out and a right eye looking in both mean "left". */
    fun gazeX(s: Map<String, Float>): Double =
        ((v(s, "eyeLookOutLeft") - v(s, "eyeLookInLeft")) + (v(s, "eyeLookInRight") - v(s, "eyeLookOutRight"))) / 2.0

    /** Positive when the person looks up. */
    fun gazeY(s: Map<String, Float>): Double =
        ((v(s, "eyeLookUpLeft") + v(s, "eyeLookUpRight")) - (v(s, "eyeLookDownLeft") + v(s, "eyeLookDownRight"))) / 2.0
}

/** A pixel rectangle inside the camera picture; [right] and [bottom] are exclusive. */
class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val area: Int get() = (right - left).coerceAtLeast(0) * (bottom - top).coerceAtLeast(0)
}

/**
 * Skin regions for the pulse, from landmark positions (normalised 0..1, as MediaPipe reports them): the forehead above the
 * brows and the two cheeks. Eyes, brows, mouth and hair are avoided because they move and change colour without a pulse.
 * MediaPipe face-mesh indices used: 10 top of forehead, 9 between the brows, 105 and 334 above the left and right brow,
 * 234 and 454 the face edges, 50 and 280 the cheek centres.
 */
object SkinRegions {
    const val TOP = 10; const val GLABELLA = 9; const val BROW_L = 105; const val BROW_R = 334
    const val EDGE_L = 234; const val EDGE_R = 454; const val CHEEK_L = 50; const val CHEEK_R = 280

    /** [lm] returns the (x, y) of a landmark index. Rectangles smaller than 16 pixels are dropped as not trustworthy. */
    fun regions(lm: (Int) -> Pair<Double, Double>, width: Int, height: Int): List<PixelRect> {
        val (_, topY) = lm(TOP)
        val (_, glabY) = lm(GLABELLA)
        val (browLx, browLy) = lm(BROW_L)
        val (browRx, browRy) = lm(BROW_R)
        val (edgeLx, _) = lm(EDGE_L)
        val (edgeRx, _) = lm(EDGE_R)
        val faceW = abs(edgeRx - edgeLx)

        fun rect(cx: Double, cy: Double, halfW: Double, halfH: Double) = PixelRect(
            ((cx - halfW) * width).toInt().coerceIn(0, width - 1), ((cy - halfH) * height).toInt().coerceIn(0, height - 1),
            ((cx + halfW) * width).toInt().coerceIn(1, width), ((cy + halfH) * height).toInt().coerceIn(1, height),
        )

        val browTop = minOf(browLy, browRy, glabY)
        val foreheadCentreY = (topY + browTop) / 2
        val foreheadHalfH = ((browTop - topY) / 2.5).coerceAtLeast(0.01)
        val foreheadHalfW = abs(browRx - browLx) * 0.45
        val out = ArrayList<PixelRect>()
        out += rect((browLx + browRx) / 2, foreheadCentreY, foreheadHalfW, foreheadHalfH)
        val (cheekLx, cheekLy) = lm(CHEEK_L)
        val (cheekRx, cheekRy) = lm(CHEEK_R)
        out += rect(cheekLx, cheekLy, faceW * 0.13, faceW * 0.13)
        out += rect(cheekRx, cheekRy, faceW * 0.13, faceW * 0.13)
        return out.filter { it.area >= 16 }
    }
}
