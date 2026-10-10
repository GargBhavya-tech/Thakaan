package com.thakaan.face

import android.content.Context
import android.graphics.Bitmap
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import kotlin.math.abs

/**
 * Finds the face in a frame with MediaPipe Face Landmarker (model bundled in assets, so no network) and reduces it to a
 * [FaceObservation]: a box, the mean colour of the skin regions, and the few blendshape scores for eyelid, jaw and gaze.
 * The frame, the 478 landmarks and the full blendshape list are dropped as soon as the call returns (invariant 7).
 * DEVICE-UNTESTED until run on the phone; check "Face model" in Diagnostics, with airplane mode on.
 */
class FaceTracker(private val ctx: Context) {
    private var landmarker: FaceLandmarker? = null
    var loadError: String? = null; private set
    private var prevCx = Double.NaN
    private var lastTsMs = -1L

    /** Returns null on success, or a short reason. Safe to call more than once. */
    fun open(): String? {
        if (landmarker != null) return null
        return try {
            val base = BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setOutputFaceBlendshapes(true)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            landmarker = FaceLandmarker.createFromOptions(ctx, options)
            loadError = null
            null
        } catch (t: Throwable) {
            loadError = (t.message ?: t.javaClass.simpleName).take(120)
            loadError
        }
    }

    /** [pixels] is the same picture as [bitmap] as ARGB ints (width x height), used only to average colour. */
    fun track(bitmap: Bitmap, pixels: IntArray, tNanos: Long): FaceObservation {
        val lm = landmarker ?: return none(tNanos)
        // MediaPipe's video mode needs strictly increasing millisecond timestamps
        var ts = tNanos / 1_000_000L
        if (ts <= lastTsMs) ts = lastTsMs + 1
        lastTsMs = ts
        val result = try { lm.detectForVideo(BitmapImageBuilder(bitmap).build(), ts) } catch (_: Throwable) { return none(tNanos) }
        val faces = result.faceLandmarks()
        if (faces.isEmpty()) { prevCx = Double.NaN; return none(tNanos) }
        val pts = faces[0]
        var minX = 1.0; var minY = 1.0; var maxX = 0.0; var maxY = 0.0
        for (p in pts) {
            val x = p.x().toDouble(); val y = p.y().toDouble()
            if (x < minX) minX = x; if (x > maxX) maxX = x; if (y < minY) minY = y; if (y > maxY) maxY = y
        }
        val box = FaceBox(minX, minY, maxX, maxY)
        val motion = if (prevCx.isNaN()) 0.0 else abs(box.centreX - prevCx) / box.width.coerceAtLeast(0.05)
        prevCx = box.centreX

        val w = bitmap.width; val h = bitmap.height
        val rects = SkinRegions.regions({ i -> val p = pts[i.coerceIn(0, pts.size - 1)]; p.x().toDouble() to p.y().toDouble() }, w, h)
        var sr = 0.0; var sg = 0.0; var sb = 0.0; var n = 0
        val perRoi = ArrayList<DoubleArray>()
        for (rc in rects) {
            var rr = 0.0; var rg = 0.0; var rb = 0.0; var rn = 0
            for (y in rc.top until rc.bottom) for (x in rc.left until rc.right) {   // every pixel: more pixels average away camera noise
                val c = pixels[y * w + x]
                rr += (c shr 16) and 0xFF; rg += (c shr 8) and 0xFF; rb += c and 0xFF
                rn++
            }
            if (rn > 0) { perRoi += doubleArrayOf(rr / rn, rg / rn, rb / rn); sr += rr; sg += rg; sb += rb; n += rn }
        }
        // a background patch in the top-left corner, if the face is not there: lighting changes show up in it too (recorded for tuning)
        var bgMean: DoubleArray? = null
        val bw = w / 6; val bh = h / 6
        if (box.left * w > bw + 4 || box.top * h > bh + 4) {
            var br = 0.0; var bg = 0.0; var bb = 0.0
            for (y in 0 until bh) for (x in 0 until bw) { val c = pixels[y * w + x]; br += (c shr 16) and 0xFF; bg += (c shr 8) and 0xFF; bb += c and 0xFF }
            val bn = (bw * bh).toDouble(); bgMean = doubleArrayOf(br / bn, bg / bn, bb / bn)
        }
        if (n == 0) return FaceObservation(tNanos, box, 0.0, 0.0, 0.0, false, 0.0, 0.0, 0.0, 0.0, motion)

        val scores = HashMap<String, Float>()
        val bs = result.faceBlendshapes()
        if (bs.isPresent && bs.get().isNotEmpty()) for (c in bs.get()[0]) scores[c.categoryName()] = c.score()
        val hasBlend = Blendshapes.usable(scores)
        return FaceObservation(
            tNanos, box, sr / n, sg / n, sb / n, hasBlend,
            Blendshapes.eyeClosure(scores), Blendshapes.jawOpen(scores), Blendshapes.gazeX(scores), Blendshapes.gazeY(scores), motion, rects, perRoi, bgMean,
        )
    }

    private fun none(tNanos: Long) = FaceObservation(tNanos, null, 0.0, 0.0, 0.0, false, 0.0, 0.0, 0.0, 0.0, 0.0)

    fun close() { landmarker?.close(); landmarker = null }

    companion object { const val MODEL_ASSET = "models/face_landmarker.task" }
}
