package com.thakaan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thakaan.inference.ModelFiles
import com.thakaan.speech.WhisperRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Speech lab: say a sentence in English, Hindi or Kannada and see what Whisper-tiny heard, how long it took, and nothing else.
 * Audio is recorded into memory, read once and dropped (invariant 7); no file, no network. Whisper-tiny is weak in Hindi and
 * weaker in Kannada, and a transcript never changes a level: the spoken digit and haan/nahi steps stay with the Vosk grammar.
 */
@Composable
fun SpeechLabScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lang by remember { mutableStateOf("en") }
    var status by remember { mutableStateOf("Pick a language, press the button and speak a short sentence.") }
    var transcript by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var mic by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { mic = it }
    val here = remember { ModelFiles.present(ctx, ModelFiles.WHISPER) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Speech lab (Whisper)", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (!here) {
                    Text("The Whisper files are not on this phone. Run tools/push_models.sh whisper on the laptop with the phone connected.", fontSize = 16.sp, color = Color(0xFF9B0000))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    WhisperRunner.LANGUAGES.forEach { l ->
                        FilterChip(selected = lang == l, onClick = { lang = l }, label = { Text(when (l) { "en" -> "English"; "hi" -> "Hindi"; else -> "Kannada" }, fontSize = 16.sp) })
                    }
                }
                Text(status, fontSize = 16.sp)
                transcript?.let { Text("\"$it\"", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                Button(
                    onClick = {
                        if (!mic) { askMic.launch(Manifest.permission.RECORD_AUDIO); return@Button }
                        busy = true; transcript = null
                        scope.launch {
                            status = "Listening for 6 seconds. Speak now..."
                            val pcm = withContext(Dispatchers.IO) { record(6) }
                            status = "Reading it (the first run loads the model)..."
                            val res = withContext(Dispatchers.Default) {
                                runCatching { WhisperRunner(ModelFiles.file(ctx, ModelFiles.WHISPER)).use { it.transcribe(pcm, lang) } }
                            }
                            res.onSuccess { transcript = it.text.let { t -> if (t.isEmpty() || (t.startsWith("[") && t.endsWith("]"))) "(no speech heard)" else t }; status = "Done in %.1f s, language %s. The recording was dropped.".format(it.millis / 1000.0, it.language) }
                                .onFailure { status = "Failed: ${it.message}" }
                            busy = false
                        }
                    },
                    enabled = here && !busy, modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text(if (mic) "Speak (6 s)" else "Allow the microphone", fontSize = 22.sp) }
                Text("Advisory only. Whisper-tiny is weak in Hindi and weaker in Kannada, and what it hears never changes a level. The spoken digit and haan/nahi checks still use the Vosk grammar. Audio stays in memory and is dropped.", fontSize = 13.sp, color = Color.DarkGray)
                OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
            }
        }
    }
}

/** Records [seconds] of mono 16 kHz audio into memory as floats in -1..1. Nothing is written to storage. */
private fun record(seconds: Int): FloatArray {
    val rate = 16000
    val minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, 16384))
    val out = FloatArray(seconds * rate)
    try {
        val buf = ShortArray(rate / 10)
        var done = 0
        rec.startRecording()
        while (done < out.size) {
            val n = rec.read(buf, 0, minOf(buf.size, out.size - done))
            if (n <= 0) break
            for (i in 0 until n) out[done + i] = buf[i] / 32768f
            done += n
        }
    } finally { rec.stop(); rec.release() }
    return out
}
