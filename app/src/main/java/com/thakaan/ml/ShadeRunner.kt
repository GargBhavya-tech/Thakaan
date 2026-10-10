package com.thakaan.ml

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Sky / tree / building segmentation (SegFormer-B0 trained on Cityscapes street scenes, converted by
 * tools/models/convert_segformer.py) applied to one rear-camera frame held pointing ahead and up. The frame stays in memory
 * and is dropped. Shadow mode: the result is shown beside the map-based shade and the GPS sky view and never decides shade.
 * Licence: NVIDIA non-commercial research use, see docs/ASSETS.md.
 */
class ShadeRunner(private val ctx: Context) {
    private var interp: Interpreter? = null
    private var loadError: String? = null

    private fun load(): Interpreter? {
        interp?.let { return it }
        if (loadError != null) return null
        return try {
            val bytes = ctx.assets.open("models/segformer_b0_cityscapes.tflite").use { it.readBytes() }
            Interpreter(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }).also { interp = it }
        } catch (t: Throwable) { loadError = t.message ?: t.javaClass.simpleName; null }
    }

    val available: Boolean get() = load() != null

    /** The upper 2:1 strip of an upright frame (where the sky and canopy are) is scaled to 512x256 and segmented. */
    fun look(frame: Bitmap): ShadeView? {
        val it = load() ?: return null
        val stripH = (frame.width / 2).coerceAtMost(frame.height)
        val strip = Bitmap.createBitmap(frame, 0, 0, frame.width, stripH)
        val scaled = Bitmap.createScaledBitmap(strip, W, H, true)
        val px = IntArray(W * H); scaled.getPixels(px, 0, W, 0, 0, W, H)
        val inBuf = ByteBuffer.allocateDirect(3 * H * W * 4).order(ByteOrder.nativeOrder())
        val mean = floatArrayOf(0.485f, 0.456f, 0.406f); val std = floatArrayOf(0.229f, 0.224f, 0.225f)
        for (c in 0 until 3) for (i in px.indices) {
            val v = ((px[i] shr (16 - 8 * c)) and 0xFF) / 255f
            inBuf.putFloat((v - mean[c]) / std[c])
        }
        inBuf.rewind()
        val outBuf = ByteBuffer.allocateDirect(OUT_H * OUT_W * 4).order(ByteOrder.nativeOrder())
        return try {
            it.run(inBuf, outBuf); outBuf.rewind()
            ShadeAnalysis.analyse(IntArray(OUT_H * OUT_W) { outBuf.getInt() }, OUT_H, OUT_W)
        } catch (_: Throwable) { null }
    }

    fun close() { interp?.close(); interp = null }

    companion object {
        const val H = 256
        const val W = 512
        const val OUT_H = 64
        const val OUT_W = 128
    }
}
