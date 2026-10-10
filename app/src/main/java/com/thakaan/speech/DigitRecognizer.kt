package com.thakaan.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import com.thakaan.brain.SpokenWord
import com.thakaan.inference.ModelFiles
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

class HeardCode(val words: List<SpokenWord>, val snrDb: Double?, val raw: String)

/**
 * Offline digit recogniser for the speech code (#34), using a small Vosk model pushed to the phone. The vocabulary is limited
 * to the ten digit words (plus unknown) so it can only answer with digits, which is all the test needs. Audio goes from the
 * microphone to the recogniser in memory and is never written anywhere (invariant 7).
 */
class DigitRecognizer(private val ctx: Context) {
    private val models = HashMap<String, Model>()

    enum class Voice(val modelDir: String, val words: List<String>) {
        EN(ModelFiles.VOSK_EN, listOf("zero", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "oh")),
        HI(ModelFiles.VOSK_HI, listOf("शून्य", "एक", "दो", "तीन", "चार", "पांच", "छह", "सात", "आठ", "नौ")),
    }

    fun available(v: Voice): Boolean = ModelFiles.present(ctx, v.modelDir)
    fun anyAvailable(): Boolean = Voice.entries.any { available(it) }
    fun hasMic(): Boolean = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun model(v: Voice): Model = models.getOrPut(v.modelDir) { Model(ModelFiles.file(ctx, v.modelDir).absolutePath) }

    /** Listens for [seconds] (blocking: call off the main thread), returns the words with timing and a rough signal-to-noise ratio. */
    @SuppressLint("MissingPermission")
    fun listen(v: Voice, seconds: Double = 7.0): HeardCode {
        require(hasMic()) { "microphone permission not granted" }
        val rate = 16000
        val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, 8192))
        val grammar = (v.words + "[unk]").joinToString(prefix = "[", postfix = "]") { "\"$it\"" }
        val recognizer = Recognizer(model(v), rate.toFloat(), grammar).apply { setWords(true) }
        val frameDb = ArrayList<Double>()
        try {
            val buf = ShortArray(rate / 10)
            val total = (seconds * rate).toInt()
            var done = 0
            rec.startRecording()
            while (done < total) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) break
                done += n
                var s = 0.0
                for (i in 0 until n) s += buf[i].toDouble() * buf[i]
                frameDb += 20 * log10(sqrt(s / n).coerceAtLeast(1.0))
                recognizer.acceptWaveForm(buf, n)
            }
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
        val raw = recognizer.finalResult
        recognizer.close()
        return HeardCode(parse(raw), snr(frameDb), raw)
    }

    fun close() { models.values.forEach { try { it.close() } catch (_: Throwable) {} }; models.clear() }

    companion object {
        /** Words with times from a Vosk result JSON. Pure, so it can be tested on the laptop. */
        fun parse(json: String): List<SpokenWord> {
            val arr = JSONObject(json).optJSONArray("result") ?: return emptyList()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                SpokenWord(o.getString("word"), o.getDouble("start"), o.getDouble("end"), o.optDouble("conf", 1.0))
            }.filter { it.text != "[unk]" }
        }

        /** Loud 20% of 100 ms frames minus quiet 20%, in dB. Null with too little audio. */
        fun snr(frameDb: List<Double>): Double? {
            if (frameDb.size < 10) return null
            val s = frameDb.sorted(); val k = (s.size / 5).coerceAtLeast(1)
            return s.takeLast(k).average() - s.take(k).average()
        }
    }
}
