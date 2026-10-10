package com.thakaan.ui

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thakaan.ml.ScgPipeline
import com.thakaan.ml.ScgRecording
import com.thakaan.ml.ScgResult
import com.thakaan.ml.ShadowModels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.sqrt

/**
 * Chest vibration lab (ticket #30, engineering): sit still, hold the phone flat against your chest (or in a snug chest
 * pocket), screen facing out, and it records the accelerometer and gyroscope at the fastest rate for 45 s, then reads the
 * heart rate from the beat vibrations (classical chain and learned denoiser). It compares nothing itself: read your
 * fingertip or watch at the same time. The numbers are saved to a private file for tuning; no pictures or sound are involved.
 */
@Composable
fun ScgLabScreen(onBack: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            val ctx = LocalContext.current
            var running by remember { mutableStateOf(false) }
            var secsLeft by remember { mutableStateOf(SECONDS) }
            var prep by remember { mutableStateOf(0) }
            var status by remember { mutableStateOf("Sit still. Hold the phone flat against your chest, screen facing out. Press Start, then you have 10 seconds to put it there. Breathe normally and do not talk.") }
            var result by remember { mutableStateOf<ScgResult?>(null) }
            var rateInfo by remember { mutableStateOf("") }
            val shadow = remember { ShadowModels(ctx) }
            DisposableEffect(Unit) { onDispose { shadow.close() } }

            LaunchedEffect(running) {
                if (!running) return@LaunchedEffect
                result = null
                // Time to put the phone on the chest first: lifting it there tilts it, and a tilt locks the recording out for 30 s.
                prep = PREP_SECONDS
                while (running && prep > 0) { delay(1000); prep-- }
                if (!running) return@LaunchedEffect
                val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                val acc = ArrayList<FloatArray>(); val gyr = ArrayList<FloatArray>()
                val t0 = SystemClock.elapsedRealtimeNanos()
                val la = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) { synchronized(acc) { acc += floatArrayOf(((e.timestamp - t0) / 1e6).toFloat(), e.values[0], e.values[1], e.values[2]) } }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                val lg = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) { synchronized(gyr) { gyr += floatArrayOf(((e.timestamp - t0) / 1e6).toFloat(), e.values[0], e.values[1], e.values[2]) } }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                val accSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                val gyrSensor = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
                if (accSensor == null) { status = "No accelerometer on this phone."; running = false; return@LaunchedEffect }
                sm.registerListener(la, accSensor, SensorManager.SENSOR_DELAY_FASTEST)
                gyrSensor?.let { sm.registerListener(lg, it, SensorManager.SENSOR_DELAY_FASTEST) }
                val start = SystemClock.elapsedRealtime()
                while (running && SystemClock.elapsedRealtime() - start < SECONDS * 1000L) {
                    delay(500)
                    secsLeft = (SECONDS - (SystemClock.elapsedRealtime() - start) / 1000).toInt().coerceAtLeast(0)
                }
                sm.unregisterListener(la); sm.unregisterListener(lg)
                if (!running) return@LaunchedEffect
                val a = synchronized(acc) { acc.toList() }; val g = synchronized(gyr) { gyr.toList() }
                if (a.size < 500) { status = "Too few samples (${a.size}). Try again."; running = false; return@LaunchedEffect }
                val dur = (a.last()[0] - a.first()[0]) / 1000.0
                rateInfo = "%d samples in %.1f s = %.0f Hz".format(a.size, dur, a.size / dur)
                val t = DoubleArray(a.size) { a[it][0] / 1000.0 }
                // gyro magnitude matched to the accelerometer's times (nearest earlier sample)
                val gm = if (g.isEmpty()) null else {
                    var j = 0
                    DoubleArray(a.size) { i -> while (j + 1 < g.size && g[j + 1][0] <= a[i][0]) j++; sqrt((g[j][1] * g[j][1] + g[j][2] * g[j][2] + g[j][3] * g[j][3]).toDouble()) }
                }
                val rec = ScgRecording(t, DoubleArray(a.size) { a[it][1].toDouble() }, DoubleArray(a.size) { a[it][2].toDouble() }, DoubleArray(a.size) { a[it][3].toDouble() }, gm)
                val r = withContext(Dispatchers.Default) { ScgPipeline.analyse(rec) { w -> shadow.denoiseScg(w) } }
                result = r
                val f = File(ctx.filesDir, "scg_lab_${System.currentTimeMillis()}.csv")
                f.bufferedWriter().use { w ->
                    w.write("t_ms,ax,ay,az,gyro\n")
                    for (i in a.indices) w.write("%.1f,%.4f,%.4f,%.4f,%.4f\n".format(a[i][0], a[i][1], a[i][2], a[i][3], gm?.get(i) ?: 0.0))
                }
                status = "Saved ${f.name} (kept on this phone only)."
                running = false
            }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Chest vibration lab", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text(status, fontSize = 16.sp)
                if (running && prep > 0) Text("Put the phone on your chest now: $prep s", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
                else if (running) Text("$secsLeft s left. Stay still.", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (rateInfo.isNotEmpty()) Text("Sensor: $rateInfo", fontSize = 14.sp, color = Color.DarkGray)
                result?.let { r ->
                    Text(
                        r.classicalBpm?.let { "Chest pulse: about %.0f beats a minute".format(it) } ?: "Chest pulse: not found (${r.reason})",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = if (r.accepted) Color(0xFF1B5E20) else Color(0xFFB00020),
                    )
                    r.modelBpm?.let { Text("Learned denoiser: about %.0f (second opinion, trained on simulated data)".format(it), fontSize = 16.sp) }
                    Text("Still windows %d of %d, rate agreement %.0f%%, axis ${r.axis}".format(r.stillWindows, r.windows, r.consistency * 100), fontSize = 14.sp, color = Color.DarkGray)
                    Text("Compare with your fingertip or watch at the same time. A match within a few beats is a pass.", fontSize = 14.sp)
                }
                if (!running) Button(onClick = { secsLeft = SECONDS; prep = 0; running = true }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start (45 s)", fontSize = 22.sp) }
                else OutlinedButton(onClick = { running = false; status = "Stopped." }, Modifier.fillMaxWidth()) { Text("Stop", fontSize = 18.sp) }
                OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
            }
        }
    }
}

private const val SECONDS = 45
private const val PREP_SECONDS = 10
