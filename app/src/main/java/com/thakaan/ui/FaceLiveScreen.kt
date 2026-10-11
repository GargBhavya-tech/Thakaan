package com.thakaan.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thakaan.face.FaceLiveSession
import com.thakaan.face.LiveView
import com.thakaan.face.OxygenCalibrationStore
import kotlinx.coroutines.delay

/**
 * Live face view: shows the front camera picture with the face and the skin regions being read drawn on it, and everything
 * the face gives, updated as it is measured. Pulse, breathing, variability, an experimental oxygen estimate and eye and yawn
 * signs. Information only: it never lowers a level, never reaches the risk engine, nothing is saved or sent, and there is no
 * temperature. The oxygen number is uncalibrated unless the worker has entered a pulse-oximeter reading.
 */
@Composable
fun FaceLiveScreen(onBack: () -> Unit) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ctx = LocalContext.current
            var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            var session by remember { mutableStateOf<FaceLiveSession?>(null) }
            var view by remember { mutableStateOf<LiveView?>(null) }
            var problem by remember { mutableStateOf<String?>(null) }
            val store = remember { OxygenCalibrationStore(ctx) }
            var oximeter by remember { mutableStateOf("") }
            var calNote by remember { mutableStateOf("") }
            var recNote by remember { mutableStateOf("") }
            var recSecs by remember { mutableStateOf(0) }
            var recOn by remember { mutableStateOf(false) }

            DisposableEffect(Unit) { onDispose { session?.stop() } }
            LaunchedEffect(session) {
                val s = session ?: return@LaunchedEffect
                while (true) {
                    view = s.view()
                    recSecs = s.recordedSeconds()
                    if (recOn && recSecs >= 170) { recNote = s.stopRecording()?.let { "Saved $it (kept on this phone only)." } ?: "Too little to save."; recOn = false }
                    s.error()?.let { problem = it; s.stop(); session = null; return@LaunchedEffect }
                    delay(100)
                }
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Face live view", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                if (session == null) {
                    Text(
                        "See what the phone reads from your face: pulse, breathing, variability, an experimental oxygen estimate and eye and yawn signs. " +
                            "Hold the phone at arm's length, face the light (not direct sun) and keep still. Nothing is saved or sent.",
                        fontSize = 17.sp,
                    )
                    problem?.let { Text(it, fontSize = 17.sp, color = Color(0xFFB00020)) }
                    if (!granted) Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Allow camera", fontSize = 20.sp) }
                    else Button(onClick = {
                        val s = FaceLiveSession(ctx)
                        val err = s.start()
                        if (err != null) problem = err else { problem = null; session = s }
                    }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start", fontSize = 22.sp) }
                    OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
                } else {
                    val v = view
                    Preview(v)
                    Cards(v)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Engineering: record the skin-colour numbers (never pictures) to a file on this phone, so the pulse method can be tuned.", fontSize = 13.sp, color = Color.DarkGray)
                        if (!recOn) OutlinedButton(onClick = { session?.startRecording(); recOn = true; recNote = "" }, Modifier.fillMaxWidth()) { Text("Record this session (up to 3 min)", fontSize = 16.sp) }
                        else Button(onClick = { recNote = session?.stopRecording()?.let { "Saved $it (kept on this phone only)." } ?: "Too little to save."; recOn = false }, Modifier.fillMaxWidth()) { Text("Stop and save recording ($recSecs s)", fontSize = 16.sp) }
                        if (recNote.isNotEmpty()) Text(recNote, fontSize = 14.sp)
                    }
                    OxygenCalibration(v, store, oximeter, { oximeter = it }, calNote) { calNote = it }
                    OutlinedButton(onClick = { session?.stop(); session = null; view = null }, Modifier.fillMaxWidth()) { Text("Stop", fontSize = 18.sp) }
                }
                Text(
                    "Experimental wellbeing readings, not medical measurements. The oxygen figure is a rough optical estimate that has not been checked against an oximeter unless you calibrate it.",
                    fontSize = 13.sp, color = Color.DarkGray,
                )
            }
        }
    }
}

@Composable
internal fun Preview(v: LiveView?) {
    val bmp = v?.picture
    val ratio = if (bmp != null && bmp.height > 0) bmp.width.toFloat() / bmp.height else 0.75f
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black)) {
        Box(Modifier.fillMaxWidth().graphicsLayer(scaleX = -1f)) { // mirrored like a selfie camera; the overlay is drawn inside so it flips with it
            Canvas(Modifier.fillMaxWidth().height((400 / ratio.coerceAtLeast(0.5f)).dp.coerceAtMost(480.dp))) {
                if (bmp != null && v != null) {
                    @Suppress("UNUSED_VARIABLE") val tick = v.frameNo // the bitmap is reused, so tie this block to new frames
                    drawImage(bmp.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()), dstOffset = IntOffset.Zero)
                    val obs = v.observation
                    obs?.box?.let { b ->
                        drawRect(Color(0xFF00C853), Offset((b.left * size.width).toFloat(), (b.top * size.height).toFloat()),
                            Size((b.width * size.width).toFloat(), (b.height * size.height).toFloat()), style = Stroke(4f))
                    }
                    obs?.rois?.forEach { r ->
                        val sx = size.width / bmp.width; val sy = size.height / bmp.height
                        drawRect(Color(0xFFFF9100), Offset(r.left * sx, r.top * sy), Size((r.right - r.left) * sx, (r.bottom - r.top) * sy), style = Stroke(3f))
                    }
                }
            }
        }
        val found = v?.observation?.box != null
        Text(
            if (v == null) "Starting the camera..." else if (found) "Face found (green). Orange boxes: the skin it reads." else "No face: keep your face in the picture",
            fontSize = 14.sp, color = Color.White, modifier = Modifier.align(Alignment.BottomStart).background(Color(0x99000000)).padding(6.dp),
        )
    }
}

@Composable
private fun Cards(v: LiveView?) {
    val s = v?.vitals
    val p = s?.pulse
    Card("Pulse", if (p?.accepted == true && p.hrBpm != null) "%.0f beats a minute".format(p.hrBpm) else p?.estimateBpm?.let { "about %.0f (not confirmed)".format(it) } ?: "measuring...",
        if (p?.accepted == true) "steady across the last seconds, two colour methods agree" else (p?.prompt ?: "needs about 25 seconds of a steady face"), p?.accepted == true)
    Card("Breathing", p?.respPerMin?.let { "%.0f a minute".format(it) } ?: "measuring...", "needs about 30 seconds of stillness", p?.respPerMin != null)
    val h = s?.hrv
    Card("Heart variability (rough)", h?.let { "RMSSD %.0f ms".format(it.rmssdMs) } ?: if (s != null && s.hrvWaitSec > 0) "wait ${s.hrvWaitSec} s" else "not clean enough",
        h?.let { "%d beats, SDNN %.0f ms; read as a trend, not a lab value".format(it.beats, it.sdnnMs) } ?: "needs 60 seconds of clean pulse", h != null)
    val o = s?.oxygen
    Card("Oxygen (experimental)", o?.let { "about %.0f%%".format(it.estimatePct) } ?: "measuring...",
        o?.let { (if (it.calibrated) "calibrated to your oximeter. " else "NOT calibrated: a rough guide only. ") + "ratio %.2f, pulse strength %.1f%% of the skin level".format(it.ratio, it.perfusionPct) } ?: "needs a clear pulse first", o != null)
    val f = s?.fatigue
    val eye = s?.eyeClosureNow
    Card("Eyes", eye?.let { "closure %.0f%%".format(it * 100) } ?: "no face",
        if (f != null && f.valid) "blinks %.0f a minute, eyes closed %.0f%% of the time, long closures ${f.longClosures}, yawns ${f.yawns}".format(f.blinksPerMin, f.perclos * 100) else (f?.reason?.prompt ?: "learning your eyes..."), eye != null)
    Card("Signal", s?.let { "face in ${it.facePercent}% of frames" } ?: "-",
        s?.let { "brightness %.0f of 255, head movement %.1f%%".format(it.brightness, it.motion * 100) } ?: "", (s?.facePercent ?: 0) >= 80)
}

@Composable
private fun Card(title: String, value: String, note: String, good: Boolean) {
    Column(
        Modifier.fillMaxWidth().border(2.dp, if (good) Color(0xFF2E7D32) else Color(0xFF9E9E9E), RoundedCornerShape(10.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, fontSize = 14.sp, color = Color.DarkGray)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (good) Color(0xFF1B5E20) else Color.Black)
        if (note.isNotEmpty()) Text(note, fontSize = 13.sp, color = Color.DarkGray)
    }
}

@Composable
private fun OxygenCalibration(v: LiveView?, store: OxygenCalibrationStore, text: String, onText: (String) -> Unit, note: String, setNote: (String) -> Unit) {
    val o = v?.vitals?.oxygen
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Have a pulse oximeter? Read it now and enter the number to calibrate the oxygen estimate for you.", fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(text, { onText(it.filter { c -> c.isDigit() }.take(3)) }, Modifier.weight(1f), label = { Text("Oximeter %") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            Button(onClick = {
                val ref = text.toIntOrNull()
                if (o == null) setNote("Wait for an oxygen estimate first.")
                else if (ref == null || ref !in 70..100) setNote("Enter the oximeter number, 70 to 100.")
                else { val base = o.estimatePct - (store.offsetPct() ?: 0.0); store.saveOffset(ref - base); setNote("Saved. Estimates now follow your oximeter.") }
            }) { Text("Calibrate", fontSize = 16.sp) }
        }
        if (note.isNotEmpty()) Text(note, fontSize = 14.sp, color = Color.DarkGray)
        if (store.offsetPct() != null) OutlinedButton(onClick = { store.clear(); setNote("Calibration removed.") }) { Text("Remove calibration", fontSize = 14.sp) }
    }
}
