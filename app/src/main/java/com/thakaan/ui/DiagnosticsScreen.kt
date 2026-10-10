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
import com.thakaan.exposure.Wbgt
import com.thakaan.exposure.WbgtInput
import com.thakaan.exposure.WbgtNative
import com.thakaan.risk.SafetyChecks
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
    var lab by remember { mutableStateOf(false) }
    if (lab) { PpgLabScreen(onBack = { lab = false }); return }
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

                Section("Light test (sun / shade / covered)") { LightTestSection() }

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

                Section("Device self-tests") {
                    val delivery = remember { AlertDelivery(ctx) }
                    var ks by remember { mutableStateOf<List<com.thakaan.audit.SelfTestLine>?>(null) }
                    var voices by remember { mutableStateOf<List<Pair<com.thakaan.risk.Lang, String>>?>(null) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ks = com.thakaan.audit.KeystoreSelfTest.run(ctx) }) { Text("Keystore signing", fontSize = 14.sp) }
                        Button(onClick = { voices = delivery.voiceStatus() }) { Text("Voices", fontSize = 14.sp) }
                        Button(onClick = { lab = true }) { Text("PPG lab", fontSize = 14.sp) }
                    }
                    var face by remember { mutableStateOf<String?>(null) }
                    Button(onClick = {
                        val t = com.thakaan.face.FaceTracker(ctx)
                        val err = t.open()
                        t.close()
                        face = err ?: "model loads from the app, no network used"
                    }) { Text("Face model", fontSize = 14.sp) }
                    face?.let { Line("Face model", it, ok = it.startsWith("model loads")) }
                    ks?.forEach { Line(it.name, it.detail, ok = it.ok) }
                    voices?.forEach { (l, s) -> Line("Voice ${l.tag}", s, ok = s == "voice installed") }
                }

                Section("Models on this phone") {
                    val scope = androidx.compose.runtime.rememberCoroutineScope()
                    val shadow = remember { com.thakaan.ml.ShadowModels(ctx) }
                    var small by remember { mutableStateOf<List<com.thakaan.ml.ModelLine>?>(null) }
                    var gemma by remember { mutableStateOf<String?>(null) }
                    var speech by remember { mutableStateOf<List<Pair<String, String>>?>(null) }
                    Text("Trained on simulated data; they run beside the rules and never decide a level.", fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { small = shadow.selfTest() }) { Text("Small models", fontSize = 14.sp) }
                        Button(onClick = {
                            gemma = "loading (up to a minute)..."
                            scope.launch(kotlinx.coroutines.Dispatchers.Default) {
                                val g = com.thakaan.risk.GemmaPhraser(ctx)
                                val st = g.load()
                                val line = if (!g.loaded) st else {
                                    val plan = com.thakaan.risk.AlertLadder.plan(com.thakaan.risk.Level.STRAINED, com.thakaan.risk.Lang.EN, 3)
                                    val ex = com.thakaan.risk.Explainer.explain(plan, listOf("wbgt_high"), g)
                                    "$st; line: \"${ex.text}\" (" + (if (ex.fromModel) "from the model" else "template used: ${ex.rejectedBecause}") + ")"
                                }
                                g.close()
                                gemma = line
                            }
                        }) { Text("Gemma", fontSize = 14.sp) }
                        Button(onClick = {
                            speech = com.thakaan.speech.DigitRecognizer.Voice.entries.map { v ->
                                val here = com.thakaan.inference.ModelFiles.present(ctx, v.modelDir)
                                val loaded = if (!here) "not on this phone" else try { org.vosk.Model(com.thakaan.inference.ModelFiles.file(ctx, v.modelDir).absolutePath).close(); "loads" } catch (t: Throwable) { "failed: ${t.message}" }
                                v.name to loaded
                            }
                        }) { Text("Speech", fontSize = 14.sp) }
                    }
                    small?.forEach { Line(it.name, "${it.status}: ${it.detail}", ok = it.status == "runs") }
                    gemma?.let { Line("Gemma 4 E2B", it, ok = it.startsWith("loaded")) }
                    speech?.forEach { (n, s) -> Line("Vosk $n", s, ok = s == "loads") }
                }

                Section("Checks") {
                    val ec = remember { runCatching { EcTempVectors.checkAssets(ctx) }.getOrNull() }
                    Line("ECTemp vectors", ec?.let { "${it.summary} pass" } ?: "could not run", ok = ec != null && ec.passed == ec.total)
                    ec?.failures?.forEach { Line("  failed", it, ok = false) }
                    // 35 C, 40% RH, strong sun, light wind at midday in Bengaluru: Liljegren WBGT must land in a plausible hot range.
                    val wb = remember {
                        Wbgt().compute(WbgtInput(1_760_000_000_000L + 6 * 3_600_000L, 12.97, 77.59, 800.0, 910.0, 35.0, 40.0, 1.5))
                    }
                    Line("WBGT native (lwbgt)", if (!WbgtNative.available) "library did not load" else wb?.let { "loads; sample WBGT %.1f C".format(it.wbgtC) } ?: "loads, sample failed", ok = wb != null && wb.wbgtC in 22.0..38.0)
                    val sc = remember { SafetyChecks.runAll() }
                    Line("Safety checks", "${sc.count { it.passed }}/${sc.size} pass", ok = sc.all { it.passed })
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
