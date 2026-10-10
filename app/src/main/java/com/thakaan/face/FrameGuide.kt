package com.thakaan.face

import kotlin.math.abs

/** A tracked face in picture coordinates, 0..1 from the top-left of the camera picture. */
class FaceBox(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centreX: Double get() = (left + right) / 2
    val centreY: Double get() = (top + bottom) / 2
}

enum class FrameState(val prompt: String) {
    OK("Face found"),
    NO_FACE("I can't see your face"),
    TOO_FAR("Move the phone closer"),
    TOO_CLOSE("Move the phone back a little"),
    OFF_CENTRE("Centre your face in the picture"),
    CUT_OFF("Your face is cut off at the edge"),
}

/** Where the face sits relative to the picture centre, so the screen can draw an arrow (each -1..1). */
class FrameGuide(val state: FrameState, val dx: Double, val dy: Double)

object FrameGuideRules {
    const val MIN_FACE_WIDTH = 0.28
    const val MAX_FACE_WIDTH = 0.80
    const val MAX_CENTRE_OFFSET = 0.18
    const val EDGE_MARGIN = 0.01

    fun guide(box: FaceBox?): FrameGuide {
        if (box == null) return FrameGuide(FrameState.NO_FACE, 0.0, 0.0)
        val dx = ((box.centreX - 0.5) * 2).coerceIn(-1.0, 1.0)
        val dy = ((box.centreY - 0.5) * 2).coerceIn(-1.0, 1.0)
        val state = when {
            box.left < EDGE_MARGIN || box.top < EDGE_MARGIN || box.right > 1 - EDGE_MARGIN || box.bottom > 1 - EDGE_MARGIN -> FrameState.CUT_OFF
            box.width < MIN_FACE_WIDTH -> FrameState.TOO_FAR
            box.width > MAX_FACE_WIDTH -> FrameState.TOO_CLOSE
            abs(box.centreX - 0.5) > MAX_CENTRE_OFFSET || abs(box.centreY - 0.5) > MAX_CENTRE_OFFSET -> FrameState.OFF_CENTRE
            else -> FrameState.OK
        }
        return FrameGuide(state, dx, dy)
    }
}

/**
 * The red-light trigger: "speed near zero plus face in frame" is only PERMISSION to try a 15 s capture, never a stop by
 * itself and never a level change. It stays closed when the speed is unknown, when the phone is too hot, or when the face
 * has not been steady for [steadySec].
 */
class StopCaptureGate(private val steadySec: Double = 2.0, private val maxSpeedMs: Double = 0.5) {
    private var okSince: Long? = null

    fun update(tNanos: Long, state: FrameState, speedMs: Double?, thermalAllowed: Boolean): Boolean {
        if (state != FrameState.OK) { okSince = null; return false }
        if (okSince == null) okSince = tNanos
        if (!thermalAllowed) return false
        if (speedMs == null || speedMs > maxSpeedMs) return false
        return (tNanos - okSince!!) / 1e9 >= steadySec
    }

    fun reset() { okSince = null }
}
