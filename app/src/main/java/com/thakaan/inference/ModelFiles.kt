package com.thakaan.inference

import android.content.Context
import java.io.File

/**
 * Where large model files live on the phone. They are pushed (adb or Office Kit), never bundled in the APK and never
 * downloaded by the app: `adb push` to /data/local/tmp, then `run-as com.thakaan cp` into [dir].
 */
object ModelFiles {
    const val GEMMA = "gemma-4-E2B-it-gpu.litertlm"
    const val VOSK_EN = "vosk-model-small-en-in-0.4"
    const val VOSK_HI = "vosk-model-small-hi-0.22"
    const val WHISPER = "whisper-tiny"   // folder: AudioEncoder, TextDecoder, MelSpectrogram (.tflite), vocab.json, added_tokens.json

    fun dir(ctx: Context): File = File(ctx.filesDir, "models").also { it.mkdirs() }
    fun file(ctx: Context, name: String): File = File(dir(ctx), name)
    fun present(ctx: Context, name: String): Boolean = file(ctx, name).let { it.exists() && (it.isDirectory || it.length() > 0) }
}
