package com.thakaan.ml

import android.content.Context
import android.graphics.Bitmap
import com.thakaan.face.FaceBox
import com.thakaan.ppg.Dsp
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** The learned face pulse for one check: the rate, how clearly one line stood out, and how many frame windows went in. */
class LearnedPulse(val bpm: Double?, val peakShare: Double, val windows: Int, val reason: String?)

/**
 * EfficientPhys (pretrained on public face-video datasets, converted by tools/models/convert_efficientphys.py) as a second
 * opinion on the face pulse. 72x72 face crops stay in memory only. The result is shown next to the POS pulse and never goes
 * to the risk engine (like the POS face pulse). Not yet checked against a reference heart rate.
 */
class LearnedPulseRunner(private val ctx: Context) {
    private var interp: Interpreter? = null
    private var loadError: String? = null

    private fun load(): Interpreter? {
        interp?.let { return it }
        if (loadError != null) return null
        return try {
            val bytes = ctx.assets.open("models/efficientphys.tflite").use { it.readBytes() }
            Interpreter(ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply { put(bytes); rewind() }).also { interp = it }
        } catch (t: Throwable) { loadError = t.message ?: t.javaClass.simpleName; null }
    }

    val available: Boolean get() = load() != null

    /** Crop the face (a square around [box] with a margin) and shrink it to 72x72 RGB bytes. */
    fun crop(bitmap: Bitmap, box: FaceBox): ByteArray {
        val w = bitmap.width; val h = bitmap.height
        val side = (maxOf(box.width * w, box.height * h) * 1.15).roundToInt().coerceIn(16, minOf(w, h))
        val cx = (box.centreX * w).roundToInt(); val cy = (((box.top + box.bottom) / 2.0) * h).roundToInt()
        val x0 = (cx - side / 2).coerceIn(0, w - side); val y0 = (cy - side / 2).coerceIn(0, h - side)
        val small = Bitmap.createScaledBitmap(Bitmap.createBitmap(bitmap, x0, y0, side, side), SIZE, SIZE, true)
        val px = IntArray(SIZE * SIZE); small.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        val out = ByteArray(SIZE * SIZE * 3)
        for (i in px.indices) { out[3 * i] = (px[i] shr 16).toByte(); out[3 * i + 1] = (px[i] shr 8).toByte(); out[3 * i + 2] = px[i].toByte() }
        return out
    }

    /** Runs the model over consecutive crops (20 new samples per 21 frames) and reads the rate from the joined wave. */
    fun analyse(crops: List<ByteArray>, fps: Double): LearnedPulse {
        val it = load() ?: return LearnedPulse(null, 0.0, 0, "model not on this phone")
        val wave = ArrayList<Double>()
        var windows = 0
        var s = 0
        val inBuf = ByteBuffer.allocateDirect((WIN + 1) * SIZE * SIZE * 3 * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(WIN * 4).order(ByteOrder.nativeOrder())
        while (s + WIN + 1 <= crops.size) {
            inBuf.clear()
            for (f in s..s + WIN) for (b in crops[f]) inBuf.putFloat((b.toInt() and 0xFF).toFloat())
            inBuf.rewind(); outBuf.clear()
            try { it.run(inBuf, outBuf) } catch (t: Throwable) { return LearnedPulse(null, 0.0, windows, "model failed: ${t.message}") }
            outBuf.rewind()
            repeat(WIN) { wave += outBuf.getFloat().toDouble() }
            windows++; s += WIN
        }
        return fromWave(wave.toDoubleArray(), fps, windows)
    }

    fun close() { interp?.close(); interp = null }

    companion object {
        const val SIZE = 72
        const val WIN = 20
        const val MIN_SHARE = 0.35

        /** The strongest line between 45 and 170 bpm, accepted only if it holds a clear share of the band's power. Pure. */
        fun fromWave(wave: DoubleArray, fps: Double, windows: Int): LearnedPulse {
            if (wave.size < 5 * fps) return LearnedPulse(null, 0.0, windows, "too little data")
            val m = wave.average()
            val x = Dsp.bandpass(DoubleArray(wave.size) { wave[it] - m }, fps, 0.7, 3.0)
            val freqs = DoubleArray(((3.0 - 0.75) / 0.01).roundToInt() + 1) { 0.75 + 0.01 * it }
            val p = Dsp.powerAt(x, fps, freqs)
            var best = 0
            for (i in p.indices) if (p[i] > p[best]) best = i
            val total = p.sum().coerceAtLeast(1e-18)
            val near = p.indices.filter { kotlin.math.abs(freqs[it] - freqs[best]) <= 0.12 }.sumOf { p[it] }
            val share = near / total
            return if (share >= MIN_SHARE) LearnedPulse(freqs[best] * 60.0, share, windows, null)
            else LearnedPulse(null, share, windows, "no clear pulse line")
        }
    }
}
