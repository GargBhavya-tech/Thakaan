package com.thakaan.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thakaan.brain.BrainResult
import com.thakaan.brain.BrainTest
import com.thakaan.brain.Fitts
import com.thakaan.brain.Tap
import com.thakaan.brain.TargetSpec
import com.thakaan.brain.Tremor
import com.thakaan.core.Provenance
import com.thakaan.ppg.CameraPpgCapture
import com.thakaan.ppg.CheckContext
import com.thakaan.ppg.CheckKind
import com.thakaan.ppg.Checks
import com.thakaan.ppg.PpgFrame
import com.thakaan.ppg.ThermalGuard
import com.thakaan.risk.Symptom
import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.Stream
import com.thakaan.shift.CheckStepId
import com.thakaan.shift.QuickCheckOutcome
import com.thakaan.shift.QuickCheckPlan
import com.thakaan.shift.ShiftEngine
import com.thakaan.shift.ShiftRuntime
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Quick check (#45): finger on the camera, hold the phone still, tap two circles, say how you feel. Under 90 s, one step
 * at a time, every failed step says what to do next, and a skipped step is recorded as skipped (never as fine).
 * Results go to the shift engine; raw frames and samples stay in RAM only. DEVICE-UNTESTED (the plan is unit-tested).
 */
@Composable
fun QuickCheckScreen(onBack: () -> Unit) {
    val engine = ShiftRuntime.engine
    val plan = remember { QuickCheckPlan() }
    val outcome = remember { QuickCheckOutcome() }
    var step by remember { mutableStateOf<CheckStepId?>(null) }
    var round by remember { mutableIntStateOf(0) }
    var finished by remember { mutableStateOf(false) }

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Quick check", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                when {
                    engine == null -> {
                        Text("Start Thakaan on the Home screen first.", fontSize = 20.sp)
                        Button(onClick = onBack) { Text("Back", fontSize = 20.sp) }
                    }
                    finished -> Summary(outcome, onBack)
                    step == null -> {
                        Text("About ${plan.totalSeconds} seconds. Four short steps.", fontSize = 20.sp)
                        Text("Do it while standing or sitting still, in shade if you can.", fontSize = 18.sp)
                        Button(onClick = { step = plan.next(null) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start", fontSize = 22.sp) }
                        OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Not now", fontSize = 18.sp) }
                    }
                    else -> {
                        val s = step!!
                        Text("Step ${plan.steps.indexOf(s) + 1} of ${plan.steps.size}: ${s.label}", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                        val advance = {
                            val n = plan.next(s)
                            if (n == null) { finish(engine, outcome); finished = true } else { step = n; round = 0 }
                        }
                        val retry = { round += 1 }
                        val skip: (String) -> Unit = { why -> outcome.skipped[s] = why; advance() }
                        when (s) {
                            CheckStepId.FINGERTIP -> FingertipStep(engine, outcome, round, advance, retry, skip)
                            CheckStepId.TREMOR -> TremorStep(outcome, round, advance, retry, skip)
                            CheckStepId.TAPPING -> TappingStep(outcome, round, advance, retry, skip)
                            CheckStepId.SYMPTOMS -> SymptomStep(outcome, advance)
                            CheckStepId.SPEECH -> skip("no speech recogniser yet")
                        }
                    }
                }
            }
        }
    }
}

private fun finish(engine: ShiftEngine, o: QuickCheckOutcome) {
    val now = SystemClock.elapsedRealtimeNanos()
    val cal = Calendar.getInstance()
    val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
    val results = ArrayList<BrainResult>()
    o.tremor?.takeIf { it.valid && !it.liftingConfound }?.let { results += BrainResult(BrainTest.TREMOR, it.bandPower, now, hour, engine.deviceId, "hand") }
    o.fitts?.takeIf { it.valid }?.let {
        results += BrainResult(BrainTest.FITTS_THROUGHPUT, it.throughputBitsPerSec, now, hour, engine.deviceId, "hand")
        results += BrainResult(BrainTest.FITTS_ERRORS, it.errorRate, now, hour, engine.deviceId, "hand")
    }
    if (results.isNotEmpty()) engine.onBrainResults(results)
    o.symptoms?.let { engine.onSymptoms(now, it, o.effort, "quick check") }
}

@Composable
private fun Summary(o: QuickCheckOutcome, onBack: () -> Unit) {
    Text("Check finished", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    o.summary().forEach { Text(it, fontSize = 18.sp) }
    Text("Your numbers stay on this phone. The Home screen shows the level.", fontSize = 16.sp, color = Color.DarkGray)
    Button(onClick = onBack, Modifier.fillMaxWidth().height(64.dp)) { Text("Done", fontSize = 22.sp) }
}

@Composable
private fun StepFailed(text: String, retry: () -> Unit, skip: (String) -> Unit) {
    Text(text, fontSize = 18.sp, color = Color(0xFFB00020))
    Button(onClick = retry, Modifier.fillMaxWidth().height(56.dp)) { Text("Try again", fontSize = 20.sp) }
    OutlinedButton(onClick = { skip("worker skipped") }, Modifier.fillMaxWidth()) { Text("Skip this step", fontSize = 18.sp) }
}

@Composable
private fun FingertipStep(engine: ShiftEngine, o: QuickCheckOutcome, round: Int, advance: () -> Unit, retry: () -> Unit, skip: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var blocked by remember(round) { mutableStateOf<String?>(null) }
    var secs by remember(round) { mutableIntStateOf(0) }
    var failed by remember(round) { mutableStateOf<String?>(null) }
    var wave by remember(round) { mutableStateOf(listOf<Float>()) }

    if (!granted) {
        Text("Thakaan needs the camera to read your pulse from your fingertip. Nothing is saved.", fontSize = 18.sp)
        Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Allow camera", fontSize = 20.sp) }
        OutlinedButton(onClick = { skip("camera not allowed") }, Modifier.fillMaxWidth()) { Text("Skip this step", fontSize = 18.sp) }
        return
    }
    val guard = remember(round) { thermalDecision(ctx) }
    if (!guard.allowed) {
        StepFailed(guard.prompt ?: "Phone is too hot for this check.", retry) { why -> skip(why) }
        return
    }
    guard.prompt?.let { Text(it, fontSize = 16.sp, color = Color.DarkGray) }
    Text("Cover the camera and the light with your fingertip. Press gently. Keep still.", fontSize = 20.sp)

    val frames = remember(round) { ArrayList<PpgFrame>() }
    val capture = remember(round) { CameraPpgCapture(ctx) }
    DisposableEffect(round, granted) {
        capture.start(
            onFrame = { f ->
                synchronized(frames) { frames += f }
                if (frames.size % 3 == 0) wave = (wave + f.red.toFloat()).takeLast(150)
            },
            onError = { failed = it },
        )
        onDispose { capture.stop() }
    }
    LaunchedEffect(round, granted) {
        val total = CheckStepId.FINGERTIP.seconds
        while (secs < total && failed == null) { delay(1000); secs += 1 }
        capture.stop()
        if (failed == null) {
            val snap = synchronized(frames) { frames.toList() }
            val stamp = engine.stamp
            val result = Checks.evaluate(
                CheckKind.QUICK, snap,
                CheckContext(System.currentTimeMillis(), stamp.state.name, stamp.secSinceStop, stamp.secSincePostureChange, engine.hotNow, Provenance.REAL),
            )
            o.heart = result
            val dts = (1 until snap.size).map { (snap[it].tNanos - snap[it - 1].tNanos) / 1e6 }
            val ap = capture.applied
            android.util.Log.i(
                "THK_PPG",
                "frames=${snap.size} dtMean=${"%.1f".format(dts.average())}ms dtMax=${"%.1f".format(dts.maxOrNull() ?: 0.0)}ms intervalsOk=${capture.frameIntervalsOk(snap)} " +
                    "accepted=${result.accepted} reasons=${result.reasons.map { it.name }} hr=${result.hrBpm} exp=${ap?.exposureNs} iso=${ap?.iso} torch=${ap?.torchLevel} " +
                    "redMin=${snap.minOfOrNull { it.red }} redMax=${snap.maxOfOrNull { it.red }}",
            )
            val t0 = snap.firstOrNull()?.tNanos ?: 0L
            runCatching {
                // Re-run the analysis the same way Checks.evaluate does, to see the measured values behind a rejection.
                val settled = snap.filter { (it.tNanos - t0) / 1e9 >= CheckKind.QUICK.settleSec }
                val cap = settled.filter { (it.tNanos - settled.first().tNanos) / 1e9 <= CheckKind.QUICK.captureSec + 0.5 }
                val a = com.thakaan.ppg.PpgPipeline.analyse(cap)
                val q = com.thakaan.ppg.Quality.evaluate(a, com.thakaan.ppg.Thresholds(minSeconds = CheckKind.QUICK.captureSec - 1.0))
                android.util.Log.i(
                    "THK_PPG",
                    "quality PI=${"%.3f".format(q.perfusionIndexPct)}% (need 0.3) corr=${"%.2f".format(q.templateCorrelation)} (need 0.85) snr=${"%.1f".format(q.snrDb)}dB (need 5) " +
                        "hrSpectral=${a.hrBpm?.let { "%.1f".format(it) }} hrPeaks=${a.peakHrBpm?.let { "%.1f".format(it) }} beats=${a.peakIdx.size} fs=${"%.1f".format(a.fs)} dropped=${a.droppedFrames}/${a.frameCount} meanRaw=${"%.1f".format(a.meanRaw)}",
                )
            }
            // Only the analysed part: during the settle window the exposure is deliberately re-aimed, which is not a dimming torch.
            val dimmed = ThermalGuard.torchDimmed(snap.filter { (it.tNanos - t0) / 1e9 >= CheckKind.QUICK.settleSec }.map { it.red }.toDoubleArray(), 30.0)
            if (result.accepted && result.hrBpm != null && !dimmed) {
                engine.onHeartCheck(SystemClock.elapsedRealtimeNanos(), result.hrBpm, 1.0, stamp, Provenance.REAL)
            } else {
                failed = if (dimmed) "The light dimmed because the phone is hot. Cool the phone in shade, then try again." else o.advice(CheckStepId.FINGERTIP)
            }
        }
        if (failed == null) advance()
    }
    if (failed == null) {
        Text("${CheckStepId.FINGERTIP.seconds - secs} s", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        Waveform(wave)
    } else {
        StepFailed(failed!!, retry, skip)
    }
}

private fun thermalDecision(ctx: Context): com.thakaan.ppg.GuardDecision {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
    val headroom = if (Build.VERSION.SDK_INT >= 30) pm.getThermalHeadroom(10) else null
    return ThermalGuard.captureAllowed(pm.currentThermalStatus, headroom)
}

@Composable
private fun Waveform(points: List<Float>) {
    Canvas(Modifier.fillMaxWidth().height(90.dp).background(Color(0xFFF2F2F2))) {
        if (points.size < 2) return@Canvas
        val lo = points.min(); val hi = points.max(); val span = (hi - lo).coerceAtLeast(1e-3f)
        val step = size.width / (points.size - 1)
        for (i in 1 until points.size) {
            val y0 = size.height * (1 - (points[i - 1] - lo) / span)
            val y1 = size.height * (1 - (points[i] - lo) / span)
            drawLine(Color(0xFFC62828), Offset((i - 1) * step, y0), Offset(i * step, y1), strokeWidth = 3f)
        }
    }
}

@Composable
private fun TremorStep(o: QuickCheckOutcome, round: Int, advance: () -> Unit, retry: () -> Unit, skip: (String) -> Unit) {
    var secs by remember(round) { mutableIntStateOf(0) }
    var failed by remember(round) { mutableStateOf<String?>(null) }
    Text("Hold the phone out at arm's length, screen up. Keep your arm still.", fontSize = 20.sp)
    LaunchedEffect(round) {
        val ax = ArrayList<Double>(); val ay = ArrayList<Double>(); val az = ArrayList<Double>()
        val collector = launch {
            SensorHub.readings.collect { r ->
                if (r.stream == Stream.ACCEL) { ax += r.values[0].toDouble(); ay += r.values[1].toDouble(); az += r.values[2].toDouble() }
            }
        }
        val total = CheckStepId.TREMOR.seconds
        while (secs < total) { delay(1000); secs += 1 }
        collector.cancel()
        val fs = ax.size / total.toDouble()
        val r = Tremor.analyse(ax.toDoubleArray(), ay.toDoubleArray(), az.toDoubleArray(), fs, null, SystemClock.elapsedRealtimeNanos())
        o.tremor = r
        if (r.valid) advance() else failed = o.advice(CheckStepId.TREMOR)
    }
    if (failed == null) Text("${CheckStepId.TREMOR.seconds - secs} s", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    else StepFailed(failed!!, retry, skip)
}

@Composable
private fun TappingStep(o: QuickCheckOutcome, round: Int, advance: () -> Unit, retry: () -> Unit, skip: (String) -> Unit) {
    var secs by remember(round) { mutableIntStateOf(0) }
    var failed by remember(round) { mutableStateOf<String?>(null) }
    var next by remember(round) { mutableIntStateOf(0) }
    val taps = remember(round) { ArrayList<Tap>() }
    var boxW by remember { mutableIntStateOf(1000) }
    var boxH by remember { mutableIntStateOf(600) }
    val targetW = 120.0
    val targets = listOf(TargetSpec(boxW * 0.2, boxH * 0.5, targetW), TargetSpec(boxW * 0.8, boxH * 0.5, targetW))

    Text("Tap the circle that is lit, then the other, as fast and as exactly as you can.", fontSize = 18.sp)
    LaunchedEffect(round) {
        val total = CheckStepId.TAPPING.seconds
        while (secs < total) { delay(1000); secs += 1 }
        val r = Fitts.analyse(taps.toList(), targets)
        o.fitts = r
        if (r.valid) advance() else failed = o.advice(CheckStepId.TAPPING)
    }
    if (failed != null) { StepFailed(failed!!, retry, skip); return }
    Text("${CheckStepId.TAPPING.seconds - secs} s", fontSize = 28.sp, fontWeight = FontWeight.Bold)
    Box(
        Modifier.fillMaxWidth().height(260.dp).background(Color(0xFFF2F2F2))
            .pointerInput(round) {
                boxW = size.width; boxH = size.height
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // hardware event time (ms since boot), never a UI-callback clock
                    taps += Tap(down.uptimeMillis * 1_000_000L, down.position.x.toDouble(), down.position.y.toDouble(), next)
                    next = 1 - next
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            for (i in 0..1) {
                val c = Offset(size.width * (if (i == 0) 0.2f else 0.8f), size.height * 0.5f)
                val lit = i == next
                drawCircle(if (lit) Color(0xFF1565C0) else Color(0xFFBDBDBD), radius = targetW.toFloat() / 2, center = c)
                drawCircle(Color.Black, radius = targetW.toFloat() / 2, center = c, style = Stroke(3f))
            }
        }
    }
}

@Composable
private fun SymptomStep(o: QuickCheckOutcome, advance: () -> Unit) {
    val picked = remember { mutableStateOf(setOf<Symptom>()) }
    var effort by remember { mutableStateOf(5f) }
    Text("Tap anything you feel right now.", fontSize = 20.sp)
    Symptom.entries.chunked(2).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { s ->
                val on = s in picked.value
                (if (on) Button(onClick = { picked.value = picked.value - s }, Modifier.weight(1f).height(56.dp)) { Text(s.name.lowercase(), fontSize = 18.sp) }
                else OutlinedButton(onClick = { picked.value = picked.value + s }, Modifier.weight(1f).height(56.dp)) { Text(s.name.lowercase(), fontSize = 18.sp) })
            }
        }
    }
    Text("How hard was the work? ${effort.toInt()} of 10", fontSize = 18.sp)
    Slider(value = effort, onValueChange = { effort = it }, valueRange = 0f..10f, steps = 9)
    Button(onClick = { o.symptoms = picked.value; o.effort = effort.toInt(); advance() }, Modifier.fillMaxWidth().height(64.dp)) {
        Text(if (picked.value.isEmpty()) "None, all fine" else "Done", fontSize = 22.sp)
    }
}
