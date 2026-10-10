package com.thakaan.speech

import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WhisperResult(val text: String, val tokens: List<Int>, val millis: Long, val language: String)

/**
 * Whisper-tiny (multilingual) on the phone through three small TFLite models from argmaxinc/whisperkit-litert (MIT): a mel
 * spectrogram model (30 s of 16 kHz audio in, 80 x 3000 out), an audio encoder (mel in, cross-attention caches out) and a text
 * decoder that is run one token at a time with its own key/value caches. Greedy decoding, no timestamps, language given by the
 * caller. Audio stays in memory (invariant 7) and nothing here touches the network. Advisory only: a transcript never changes a
 * level. Tiny is weak in Hindi and weaker in Kannada, so the screen says so. DEVICE-UNTESTED until run on the phone.
 */
class WhisperRunner(private val dir: File) : AutoCloseable {
    companion object {
        const val SAMPLES = 480_000          // 30 s at 16 kHz: the models take exactly this
        const val MAX_NEW_TOKENS = 60
        private const val KV = 224 * 6 * 64  // one self-attention cache, floats
        private const val CROSS = 4 * 6 * 1500 * 64
        private const val VOCAB = 51865
        val LANGUAGES = listOf("en", "hi", "kn")

        /** Reads a 16-bit PCM WAV (any rate, mono or stereo) as mono 16 kHz floats, or null if it is not one. For the self-test clip. */
        fun readWav(f: File): FloatArray? {
            val b = java.nio.ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
            if (b.limit() < 44 || b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157) return null
            var pos = 12; var rate = 0; var ch = 0; var bits = 0; var dataAt = -1; var dataLen = 0
            while (pos + 8 <= b.limit()) {
                val id = b.getInt(pos); val len = b.getInt(pos + 4)
                if (id == 0x20746d66) { ch = b.getShort(pos + 10).toInt(); rate = b.getInt(pos + 12); bits = b.getShort(pos + 22).toInt() }
                if (id == 0x61746164) { dataAt = pos + 8; dataLen = minOf(len, b.limit() - dataAt); break }
                pos += 8 + len + (len and 1)
            }
            if (dataAt < 0 || bits != 16 || ch !in 1..2 || rate <= 0) return null
            val n = dataLen / (2 * ch)
            val mono = FloatArray(n) { i -> var v = 0f; for (c in 0 until ch) v += b.getShort(dataAt + (i * ch + c) * 2) / 32768f; v / ch }
            val outN = (n.toLong() * 16000 / rate).toInt()
            return FloatArray(outN) { i -> val t = i * rate / 16000.0; val a = t.toInt().coerceAtMost(n - 1); val c = (a + 1).coerceAtMost(n - 1); val fr = (t - a).toFloat(); mono[a] * (1 - fr) + mono[c] * fr }
        }
    }

    private val tok = WhisperTokenizer(dir)
    private val mel = Interpreter(File(dir, "MelSpectrogram.tflite"), Interpreter.Options().setNumThreads(4))
    private val enc = Interpreter(File(dir, "AudioEncoder.tflite"), Interpreter.Options().setNumThreads(4))
    private val dec = Interpreter(File(dir, "TextDecoder.tflite"), Interpreter.Options().setNumThreads(4))

    private fun floats(n: Int): ByteBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder())

    /** [pcm] is mono 16 kHz in -1..1; longer than 30 s is cut, shorter is padded with silence. */
    fun transcribe(pcm: FloatArray, language: String): WhisperResult {
        val t0 = System.nanoTime()
        val lang = tok.special("<|$language|>") ?: throw IllegalArgumentException("no such language token: $language")
        val sot = tok.special("<|startoftranscript|>")!!; val transcribe = tok.special("<|transcribe|>")!!; val noTs = tok.special("<|notimestamps|>")!!

        val audio = floats(SAMPLES)
        for (i in 0 until SAMPLES) audio.putFloat(if (i < pcm.size) pcm[i] else 0f)
        audio.rewind()
        val melOut = floats(80 * 3000)
        mel.run(audio, melOut)
        melOut.rewind()

        val kCross = floats(CROSS); val vCross = floats(CROSS)
        enc.runForMultipleInputsOutputs(arrayOf<Any>(melOut), mapOf(enc.getOutputIndex("k_cache_cross") to kCross, enc.getOutputIndex("v_cache_cross") to vCross))
        kCross.rewind(); vCross.rewind()

        // two sets of self caches: the decoder reads one and writes the other, then they swap
        var cur = Array(8) { floats(KV) }; var nxt = Array(8) { floats(KV) }
        val names = (0 until 4).flatMap { listOf("k_cache_self_$it", "v_cache_self_$it") }
        val outNames = (0 until 4).flatMap { listOf("k_cache_$it", "v_cache_$it") }
        val x = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder()); val index = ByteBuffer.allocateDirect(8).order(ByteOrder.nativeOrder())
        val logits = floats(VOCAB)
        val inputIdx = HashMap<String, Int>().also { m -> (names + listOf("x", "index", "k_cache_cross", "v_cache_cross")).forEach { m[it] = dec.getInputIndex(it) } }
        val outIdx = HashMap<String, Int>().also { m -> (outNames + "logits").forEach { m[it] = dec.getOutputIndex(it) } }

        val prompt = listOf(sot, lang, transcribe, noTs)
        val generated = ArrayList<Int>()
        var token = prompt[0]; var pos = 0
        while (generated.size < MAX_NEW_TOKENS && pos < 220) {
            x.rewind(); x.putLong(token.toLong()); x.rewind()
            index.rewind(); index.putLong(pos.toLong()); index.rewind()
            val inputs = arrayOfNulls<Any>(dec.inputTensorCount)
            for (i in names.indices) { cur[i].rewind(); inputs[inputIdx.getValue(names[i])] = cur[i] }
            inputs[inputIdx.getValue("x")] = x; inputs[inputIdx.getValue("index")] = index
            inputs[inputIdx.getValue("k_cache_cross")] = kCross; inputs[inputIdx.getValue("v_cache_cross")] = vCross
            val outputs = HashMap<Int, Any>()
            for (i in outNames.indices) { nxt[i].rewind(); outputs[outIdx.getValue(outNames[i])] = nxt[i] }
            logits.rewind(); outputs[outIdx.getValue("logits")] = logits
            @Suppress("UNCHECKED_CAST") dec.runForMultipleInputsOutputs(inputs as Array<Any>, outputs)
            val t = cur; cur = nxt; nxt = t
            pos++
            if (pos < prompt.size) { token = prompt[pos]; continue }
            // greedy over text tokens and end-of-text only: language, task and timestamp tokens are not allowed here
            logits.rewind()
            var best = -1; var bestV = -Float.MAX_VALUE
            for (i in 0..WhisperTokenizer.EOT) { val v = logits.getFloat(i * 4); if (v > bestV) { bestV = v; best = i } }
            if (best == WhisperTokenizer.EOT || best < 0) break
            generated += best; token = best
        }
        return WhisperResult(tok.decode(generated), generated, (System.nanoTime() - t0) / 1_000_000, language)
    }

    override fun close() { mel.close(); enc.close(); dec.close() }
}
