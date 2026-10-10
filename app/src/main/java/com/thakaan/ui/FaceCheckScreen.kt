package com.thakaan.ui

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thakaan.face.FaceCheckOutcome
import com.thakaan.face.FaceCheckSession
import com.thakaan.face.FaceLive
import com.thakaan.face.FatigueCalibrationStore
import com.thakaan.face.FatigueSignals
import com.thakaan.shift.ShiftRuntime
import kotlinx.coroutines.delay
import java.util.Calendar

private enum class Phase { INTRO, RUNNING, RESULT }

/**
 * Face check (#31): front camera, about 40 s. Shows a pulse and breathing estimate as information only, and feeds
 * the eyelid and yawn signals to the brain battery against the worker's own baseline. It never replaces the fingertip
 * check, never lowers a level, and nothing it sees is saved. It runs on its own from the Check tab, and as a step of the
 * Quick check for the kinds of work where a face can be read (see WorkType).
 */
@Composable
fun FaceCheckScreen(onBack: () -> Unit) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            FaceCheckBody(embedded = false, onDone = onBack)
        }
    }
}

/**
 * The face check itself. [embedded] puts it inside another scrolling screen (the Quick check): no title, no own scroll, a
 * "Skip this step" button instead of Back, and "Continue" instead of Done. [onFinished] says whether the camera read something
 * usable and gives one line for the Quick check summary.
 */
@Composable
fun FaceCheckBody(embedded: Boolean, onDone: () -> Unit, onSkip: (() -> Unit)? = null, onFinished: (Boolean, String) -> Unit = { _, _ -> }) {
    val ctx = LocalContext.current
    val store = remember { FatigueCalibrationStore(ctx) }
    var phase by remember { mutableStateOf(Phase.INTRO) }
    var baseline by remember { mutableStateOf(false) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var session by remember { mutableStateOf<FaceCheckSession?>(null) }
    var live by remember { mutableStateOf<FaceLive?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    var outcome by remember { mutableStateOf<FaceCheckOutcome?>(null) }
    var saved by remember { mutableStateOf("") }

    DisposableEffect(Unit) { onDispose { session?.abort() } }

    LaunchedEffect(phase, session) {
        val s = session ?: return@LaunchedEffect
        if (phase != Phase.RUNNING) return@LaunchedEffect
        while (!s.done()) { live = s.live(); delay(250) }
        val o = s.finish(store.load(), baseline)
        outcome = o
        saved = saveResults(o, baseline, store)
        session = null
        phase = Phase.RESULT
        val usable = o.error == null && o.fatigue?.valid == true
        onFinished(usable, if (usable) "Face check done" else "Face check not usable: ${o.error ?: o.fatigue?.reason?.prompt ?: "try again in better light"}")
    }

    val frame = if (embedded) Modifier.fillMaxWidth() else Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)
    Column(frame, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (!embedded) Text("Face check", fontSize = 28.sp, fontWeight = FontWeight.Bold)
        when (phase) {
            Phase.INTRO -> {
                Text(
                    "Hold the phone at arm's length and look at the screen, in good light but not direct sun. Keep still for about 40 seconds. " +
                        "It looks at your eyes and skin colour. Nothing is saved or sent. It does not replace the finger check.",
                    fontSize = 18.sp,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = baseline, onCheckedChange = { baseline = it })
                    Text("I feel rested: use this as my normal", fontSize = 17.sp)
                }
                problem?.let { Text(it, fontSize = 18.sp, color = Color(0xFFB00020)) }
                if (!granted) {
                    Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Allow camera", fontSize = 20.sp) }
                } else {
                    Button(onClick = {
                        val s = FaceCheckSession(ctx)
                        val err = s.start()
                        if (err != null) { problem = err } else { problem = null; session = s; phase = Phase.RUNNING }
                    }, Modifier.fillMaxWidth().height(64.dp)) { Text("Start", fontSize = 22.sp) }
                }
                if (embedded) OutlinedButton(onClick = { onSkip?.invoke() }, Modifier.fillMaxWidth()) { Text("Skip this step", fontSize = 18.sp) }
                else OutlinedButton(onClick = onDone, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
            }
            Phase.RUNNING -> {
                val l = live
                Text(l?.state?.prompt ?: "Starting the camera", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
                Text("${l?.secondsLeft ?: (FaceCheckSession.SETTLE_SEC + FaceCheckSession.CAPTURE_SEC)} s left", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Face seen in ${l?.facePercent ?: 0}% of frames" + if (l?.cameraLocked == true) "" else " (camera settling)", fontSize = 16.sp, color = Color.DarkGray)
                OutlinedButton(onClick = { session?.abort(); session = null; phase = Phase.INTRO }, Modifier.fillMaxWidth()) { Text("Cancel", fontSize = 18.sp) }
            }
            Phase.RESULT -> Result(outcome, saved, doneLabel = if (embedded) "Continue" else "Done", onAgain = { outcome = null; phase = Phase.INTRO }, onBack = onDone)
        }
    }
}

/** Gives the engine what is safe to give: eyelid and yawn tests only; the face pulse is shown, never fed to the risk engine. */
private fun saveResults(o: FaceCheckOutcome, baseline: Boolean, store: FatigueCalibrationStore): String {
    val f = o.fatigue ?: return "Nothing saved."
    if (!f.valid) return "Nothing saved: ${f.reason?.prompt ?: "not usable"}."
    val engine = ShiftRuntime.engine ?: return "Nothing saved: start sensing first."
    val cal = Calendar.getInstance()
    val hour = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60.0
    val results = FatigueSignals.toBrainResults(f, android.os.SystemClock.elapsedRealtimeNanos(), hour, engine.deviceId)
    return if (baseline) {
        o.newCalibration?.let { store.save(it) }
        results.forEach { engine.battery.addBaseline(it) }
        "Saved as your normal."
    } else {
        engine.onBrainResults(results)
        "Compared with your normal."
    }
}

@Composable
private fun Result(o: FaceCheckOutcome?, saved: String, doneLabel: String, onAgain: () -> Unit, onBack: () -> Unit) {
    if (o == null) return
    if (o.error != null) {
        Text("The face check could not run: ${o.error}", fontSize = 18.sp, color = Color(0xFFB00020))
    } else {
        Text("Check finished (${o.frames} frames)", fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        val r = o.rppg
        if (r != null && r.accepted && r.hrBpm != null) {
            Text("Pulse about %.0f beats a minute (from the face: less certain than the finger)".format(r.hrBpm), fontSize = 18.sp)
            r.respPerMin?.let { Text("Breathing about %.0f a minute".format(it), fontSize = 18.sp) }
        } else {
            Text("Pulse: not reliable here. ${r?.prompt ?: ""}", fontSize = 18.sp)
        }
        o.learnedPulse?.let { lp ->
            if (lp.bpm != null) Text("Second opinion (learned model): about %.0f beats a minute. Shown for comparison only.".format(lp.bpm), fontSize = 16.sp, color = Color.DarkGray)
            else Text("Second opinion (learned model): no clear pulse (${lp.reason ?: "unclear"}).", fontSize = 16.sp, color = Color.DarkGray)
        }
        val f = o.fatigue
        if (f != null && f.valid) {
            Text("Blinks about %.0f a minute, yawns %d, long eye closures %d".format(f.blinksPerMin, f.yawns, f.longClosures), fontSize = 18.sp)
        } else {
            Text("Tiredness signs: no reading. ${f?.reason?.prompt ?: ""}", fontSize = 18.sp)
        }
        Text(saved, fontSize = 16.sp, color = Color.DarkGray)
        Text("Your numbers stay on this phone. It never replaces the finger check.", fontSize = 14.sp, color = Color.DarkGray)
    }
    Button(onClick = onAgain, Modifier.fillMaxWidth().height(56.dp)) { Text("Again", fontSize = 20.sp) }
    OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text(doneLabel, fontSize = 18.sp) }
}
