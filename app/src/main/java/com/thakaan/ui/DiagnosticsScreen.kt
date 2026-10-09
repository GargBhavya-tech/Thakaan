package com.thakaan.ui

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.body.EcTempVectors
import com.thakaan.inference.Backend
import com.thakaan.inference.ModelHost
import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.Stream
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Engineering view. Shows sensor health, thermal headroom, where models really run, and the test slots filled by later tickets. */
@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            val ctx = LocalContext.current
            val stats by SensorHub.stats.collectAsStateWithLifecycle()
            var nowNs by remember { mutableLongStateOf(SystemClock.elapsedRealtimeNanos()) }
            var headroom by remember { mutableStateOf<Float?>(null) }
            var thermalStatus by remember { mutableStateOf(0) }
            LaunchedEffect(Unit) {
                val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                while (true) {
                    nowNs = SystemClock.elapsedRealtimeNanos()
                    headroom = pm.getThermalHeadroom(10).takeIf { !it.isNaN() }
                    thermalStatus = pm.currentThermalStatus
                    delay(2000)
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(onClick = onBack) { Text("Back", fontSize = 18.sp) }
                Text("Diagnostics", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("${Build.MODEL}, SoC ${Build.SOC_MODEL}, Android ${Build.VERSION.RELEASE}", fontSize = 14.sp)

                Section("Sensor health") {
                    Stream.entries.forEach { s ->
                        val st = stats[s]
                        val ageS = st?.let { (nowNs - it.lastTNanos) / 1e9 }
                        val state = when {
                            st == null -> "no data"
                            ageS != null && ageS > 3.0 -> "STALE %.0f s".format(ageS)
                            else -> "ok %.1f Hz".format(st.ratePerSec)
                        }
                        Line(s.label, state, ok = state.startsWith("ok"))
                    }
                }

                Section("Thermal") {
                    // Phone-case heat for throttling decisions only. Not a body-temperature reading.
                    Line("Thermal headroom (10 s)", headroom?.let { "%.2f".format(it) } ?: "unavailable", ok = headroom != null)
                    Line("Thermal status", thermalStatus.toString(), ok = thermalStatus == 0)
                }

                Section("Model compute") {
                    ModelHost.init(ctx)
                    var tick by remember { mutableStateOf(0) }
                    val scope = rememberCoroutineScope()
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf<Pair<String, Backend?>>("Load tiny model" to null, "Only GPU" to Backend.GPU, "Only CPU" to Backend.CPU).forEach { (label, only) ->
                            Button(onClick = {
                                scope.launch(Dispatchers.Default) {
                                    try { ModelHost.loadTinyAndCheck(ctx, only) } catch (t: Throwable) { ModelHost.lastCheck = "failed: ${t.message}" }
                                    tick++
                                }
                            }) { Text(label, fontSize = 14.sp) }
                        }
                    }
                    key(tick) {}
                    if (ModelHost.lastCheck.isNotEmpty()) Line("Output check", ModelHost.lastCheck, ok = !ModelHost.lastCheck.startsWith("failed"))
                    val seam = ModelHost.seam
                    val r = seam.report
                    if (r == null) {
                        Line("Model", "no model loaded", ok = false)
                        seam.failures.forEach { Line("Back end failed", it, ok = false) }
                    } else {
                        Line("Back end", r.actual.label, ok = r.fullyOnRequested)
                        Line("Delegated ops", r.summary, ok = r.fullyOnRequested)
                        if (r.note.isNotEmpty()) Line("Note", r.note, ok = true)
                    }
                }

                Section("Checks") {
                    val ec = remember { runCatching { EcTempVectors.checkAssets(ctx) }.getOrNull() }
                    Line("ECTemp vectors", ec?.let { "${it.summary} pass" } ?: "could not run", ok = ec != null && ec.passed == ec.total)
                    ec?.failures?.forEach { Line("  failed", it, ok = false) }
                    Line("Safety checks", "not run yet (#42)", ok = false)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Line(label: String, value: String, ok: Boolean) {
    Row(
        Modifier.fillMaxWidth().border(1.dp, Color.Gray, RoundedCornerShape(8.dp)).padding(10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.sp)
        // Colour plus a word/mark so it still reads without colour.
        Text((if (ok) "✓ " else "✗ ") + value, fontSize = 15.sp, color = if (ok) Color(0xFF1B7F3B) else Color(0xFF9B0000), fontWeight = FontWeight.SemiBold)
    }
}
