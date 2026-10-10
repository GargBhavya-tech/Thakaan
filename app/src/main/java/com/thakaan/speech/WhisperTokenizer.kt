package com.thakaan.speech

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Turns Whisper token ids back into text. Whisper uses a byte-level BPE (GPT-2 style): every token is a string of "printable"
 * stand-in characters for raw bytes, so decoding maps each character back to its byte and reads the bytes as UTF-8 (which is
 * what makes Hindi and Kannada come out right). Special tokens (language, task, timestamps) are not text and are skipped.
 * Files: vocab.json {token string: id} and added_tokens.json {"<|name|>": id}, both from openai/whisper-tiny (MIT).
 */
class WhisperTokenizer(dir: File) {
    private val idToToken: Array<String?>
    private val special = HashMap<String, Int>()
    private val byteOf = HashMap<Char, Int>()

    init {
        val vocab = JSONObject(File(dir, "vocab.json").readText(Charsets.UTF_8))
        val added = JSONObject(File(dir, "added_tokens.json").readText(Charsets.UTF_8))
        var max = 0
        vocab.keys().forEach { max = maxOf(max, vocab.getInt(it)) }
        added.keys().forEach { max = maxOf(max, added.getInt(it)) }
        idToToken = arrayOfNulls(max + 1)
        vocab.keys().forEach { idToToken[vocab.getInt(it)] = it }
        added.keys().forEach { special[it] = added.getInt(it) }
        // the GPT-2 byte-to-character table: printable bytes map to themselves, the rest to code points from 256 up
        val printable = ((33..126) + (161..172) + (174..255)).toMutableList()
        val chars = printable.toMutableList()
        var n = 0
        for (b in 0..255) if (b !in printable) { printable += b; chars += 256 + n; n++ }
        for (i in printable.indices) byteOf[chars[i].toChar()] = printable[i]
    }

    /** Id of a special token such as "<|startoftranscript|>" or "<|hi|>", or null if this vocabulary has none. */
    fun special(name: String): Int? = special[name]

    fun decode(ids: List<Int>): String {
        val out = ByteArrayOutputStream()
        for (id in ids) {
            if (id >= SPECIAL_START) continue
            val tok = idToToken.getOrNull(id) ?: continue
            for (c in tok) out.write(byteOf[c] ?: '?'.code)
        }
        return out.toString(Charsets.UTF_8.name()).trim()
    }

    companion object {
        /** Ids from here up are special tokens (end of text is 50257 and is handled by the caller). */
        const val SPECIAL_START = 50257
        const val EOT = 50257
    }
}
