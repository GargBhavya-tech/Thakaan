package com.thakaan.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thakaan.ppg.CameraPpgCapture
import com.thakaan.ppg.LiveWave
import com.thakaan.ppg.PpgFrame
import kotlinx.coroutines.delay
import java.io.File

/**
 * PPG lab (engineering tool, Diagnostics): records the fingertip's red, green and blue means for 45 s, draws the live PULSE
 * component (not the raw level, which drifts far more than a heartbeat), and saves the numbers to a file in the app's private
 * folder so the processing can be tuned on real traces. The file holds only colour means over time, never an image.
 */
@Composable
fun PpgLabScreen(onBack: () -> Unit) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ctx = LocalContext.current
            var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
            val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
            var running by remember { mutableStateOf(false) }
            var secsLeft by remember { mutableStateOf(SECONDS) }
            var status by remember { mutableStateOf("Press Start, cover the rear camera and the torch with your fingertip, press gently and keep still.") }
            var redWave by remember { mutableStateOf(listOf<Float>()) }
            var greenWave by remember { mutableStateOf(listOf<Float>()) }
            var strength by remember { mutableStateOf("") }
            var liveHr by remember { mutableStateOf("") }
            val frames = remember { ArrayList<PpgFrame>() }
            val capture = remember { CameraPpgCapture(ctx) }

            DisposableEffect(Unit) { onDispose { capture.stop() } }

            LaunchedEffect(running) {
                if (!running) return@LaunchedEffect
                synchronized(frames) { frames.clear() }
                val startMs = SystemClock.elapsedRealtime()
                var failed = false
                // phone motion during the recording (accelerometer), to tell which seconds a moving hand corrupted
                val acc = ArrayList<FloatArray>()
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) { synchronized(acc) { acc += floatArrayOf((SystemClock.elapsedRealtime() - startMs).toFloat(), e.values[0], e.values[1], e.values[2]) } }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
                capture.start(onFrame = { f -> synchronized(frames) { frames += f } }, onError = { status = "Camera: $it"; failed = true })
                while (running && !failed && SystemClock.elapsedRealtime() - startMs < SECONDS * 1000L) {
                    delay(500)
                    secsLeft = (SECONDS - (SystemClock.elapsedRealtime() - startMs) / 1000).toInt().coerceAtLeast(0)
                    val snap = synchronized(frames) { frames.takeLast(240).toList() } // last 8 s at 30 fps
                    redWave = LiveWave.pulse(snap.map { it.red }); greenWave = LiveWave.pulse(snap.map { it.green })
                    liveHr = com.thakaan.ppg.FingertipPos.liveBpm(synchronized(frames) { frames.takeLast(450).toList() })?.let { "live pulse estimate: about %.0f beats a minute".format(it) } ?: ""
                    strength = "pulse strength: red %.2f%%, green %.2f%%".format(LiveWave.strengthPct(snap.map { it.red }), LiveWave.strengthPct(snap.map { it.green }))
                }
                capture.stop()
                sm.unregisterListener(listener)
                if (running && !failed) {
                    val all = synchronized(frames) { frames.toList() }
                    val f = File(ctx.filesDir, "ppg_lab_${System.currentTimeMillis()}.csv")
                    f.bufferedWriter().use { w ->
                        w.write("t_ms,r,g,b\n")
                        val t0 = all.firstOrNull()?.tNanos ?: 0L
                        for (p in all) w.write("%d,%.3f,%.3f,%.3f\n".format((p.tNanos - t0) / 1_000_000L, p.red, p.green, p.blue))
                    }
                    File(ctx.filesDir, f.name.removeSuffix(".csv") + "_acc.csv").bufferedWriter().use { w ->
                        w.write("t_ms,ax,ay,az\n")
                        synchronized(acc) { for (a in acc) w.write("%.0f,%.3f,%.3f,%.3f\n".format(a[0], a[1], a[2], a[3])) }
                    }
                    status = "Saved ${all.size} frames to ${f.name} (kept on this phone only)."
                }
                running = false
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("PPG lab", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(status, fontSize = 16.sp)
                if (running) Text("$secsLeft s left", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Red (pulse component, last 8 s)", fontSize = 14.sp)
                Wave(redWave, Color(0xFFB00020))
                Text("Green (pulse component, last 8 s)", fontSize = 14.sp)
                Wave(greenWave, Color(0xFF1B7F3B))
                if (liveHr.isNotEmpty()) Text(liveHr, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                if (strength.isNotEmpty()) Text(strength, fontSize = 15.sp)
                if (!granted) {
                    Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Allow camera", fontSize = 20.sp) }
                } else if (!running) {
                    Button(onClick = { secsLeft = SECONDS; running = true }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start (45 s)", fontSize = 22.sp) }
                } else {
                    OutlinedButton(onClick = { running = false; status = "Stopped." }, Modifier.fillMaxWidth()) { Text("Stop", fontSize = 18.sp) }
                }
                OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
            }
        }
    }
}

private const val SECONDS = 45

@Composable
private fun Wave(points: List<Float>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(110.dp).background(Color(0xFFF2F2F2))) {
        if (points.size < 2) return@Canvas
        val lo = points.min(); val hi = points.max(); val span = (hi - lo).coerceAtLeast(1e-3f)
        val step = size.width / (points.size - 1)
        for (i in 1 until points.size) {
            val y0 = size.height - (points[i - 1] - lo) / span * size.height
            val y1 = size.height - (points[i] - lo) / span * size.height
            drawLine(color, Offset((i - 1) * step, y0), Offset(i * step, y1), strokeWidth = 4f)
        }
        drawRect(Color.Gray, style = Stroke(1f))
    }
}
