package com.thakaan

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.thakaan.shift.ShiftRuntime
import com.thakaan.sensorhub.Replayer
import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.SensorHubService
import com.thakaan.sensorhub.StreamRecorder
import com.thakaan.ui.ChhayaScreen
import com.thakaan.ui.ShiftReportScreen
import com.thakaan.ui.DiagnosticsScreen
import com.thakaan.settings.DailyCheckIn
import com.thakaan.settings.PrefsCheckInStore
import com.thakaan.settings.PrefsProfileStore
import com.thakaan.settings.checkInDue
import com.thakaan.ui.CheckInScreen
import com.thakaan.ui.AlertDelivery
import com.thakaan.ui.OnboardingScreen
import com.thakaan.ui.FaceCheckScreen
import com.thakaan.ui.FaceLiveScreen
import com.thakaan.ui.QuickCheckScreen
import com.thakaan.ui.SafetyScreen
import com.thakaan.ui.HomeScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The demo and the 10-minute stream test run with the screen on.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // Edge-to-edge is enforced on new Android versions: keep every screen clear of the status bar and camera cutout.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content)) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            androidx.core.view.WindowInsetsCompat.CONSUMED
        }
        val store = PrefsProfileStore(applicationContext)
        val delivery = AlertDelivery(applicationContext)
        val checkIns = PrefsCheckInStore(applicationContext)
        val today = { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date()) }
        // Hands today's answer (only "slept poorly" is used; unknown stays unknown) to the engine once it exists.
        val applyCheckIn = { ShiftRuntime.engine?.onCheckIn(checkIns.load()?.takeIf { it.date == today() }?.sleptPoorly, null); Unit }
        setContent {
            var profile by remember { mutableStateOf(store.load()) }
            if (profile == null) {
                OnboardingScreen(onDone = { store.save(it); profile = it })
                return@setContent
            }
            var checkInShown by remember { mutableStateOf(checkInDue(checkIns, today())) }
            if (checkInShown) {
                CheckInScreen(onAnswer = { slept, ate, unwell -> checkIns.save(DailyCheckIn(today(), slept, ate, unwell)); checkInShown = false })
                return@setContent
            }
            var running by remember { mutableStateOf(false) }
            val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                // Start whatever the grants allow; the service skips location if it was refused.
                start()
                ShiftRuntime.start(applicationContext, lifecycleScope, profile = profile, onAlert = { delivery.deliver(it) })
                applyCheckIn()
                running = true
            }
            var recording by remember { mutableStateOf(false) }
            var replaying by remember { mutableStateOf(false) }
            val sessionFile = remember { File(filesDir, "session.thkr") }
            val recorder = remember { StreamRecorder(sessionFile) }
            var diagnostics by remember { mutableStateOf(false) }
            var chhaya by remember { mutableStateOf(false) }
            var safety by remember { mutableStateOf(false) }
            var shiftReport by remember { mutableStateOf(false) }
            if (shiftReport) { ShiftReportScreen(onBack = { shiftReport = false }); return@setContent }
            var quick by remember { mutableStateOf(false) }
            var face by remember { mutableStateOf(false) }
            if (quick) { QuickCheckScreen(onBack = { quick = false }); return@setContent }
            var faceLive by remember { mutableStateOf(false) }
            if (faceLive) { FaceLiveScreen(onBack = { faceLive = false }); return@setContent }
            if (face) { FaceCheckScreen(onBack = { face = false }); return@setContent }
            if (chhaya) { ChhayaScreen(onBack = { chhaya = false }); return@setContent }
            if (safety) { SafetyScreen(onBack = { safety = false }); return@setContent }
            if (diagnostics) {
                DiagnosticsScreen(onBack = { diagnostics = false })
                return@setContent
            }
            HomeScreen(
                onOpenDiagnostics = { diagnostics = true },
                onOpenChhaya = { chhaya = true },
                onOpenShiftReport = { shiftReport = true },
                onOpenSafety = { safety = true },
                onOpenQuickCheck = { quick = true },
                onOpenFaceCheck = { face = true },
                onOpenFaceLive = { faceLive = true },
                running = running,
                recording = recording,
                replaying = replaying,
                onToggleHub = {
                    if (running) {
                        lifecycleScope.launch { recorder.stop() }
                        recording = false
                        startService(Intent(this, SensorHubService::class.java).setAction(SensorHubService.ACTION_STOP))
                        ShiftRuntime.stop()
                        running = false
                    } else {
                        perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.POST_NOTIFICATIONS))
                    }
                },
                onToggleRecord = {
                    if (recording) {
                        lifecycleScope.launch { recorder.stop(); Log.i("THK_REC", "stopped, ${recorder.recorded} readings, ${sessionFile.length()} bytes") }
                        recording = false
                    } else {
                        recorder.start(lifecycleScope)
                        recording = true
                    }
                },
                onReplay = { speed ->
                    if (sessionFile.exists()) {
                        replaying = true
                        SensorHub.reset()
                        ShiftRuntime.start(applicationContext, lifecycleScope, sessionFile.lastModified() / 1000 - 600, profile, { delivery.deliver(it) })
                        applyCheckIn()
                        lifecycleScope.launch {
                            val ticker = launch { while (true) { SensorHub.snapshot(); logStats(); delay(1000) } }
                            Replayer().play(sessionFile, speed)
                            SensorHub.snapshot(); logStats()
                            ticker.cancel()
                            replaying = false
                        }
                    }
                },
            )
        }
    }

    private fun logStats() {
        Log.i("THK_REPLAY", SensorHub.stats.value.entries.joinToString(" ") { (s, st) ->
            "${s.name}=${st.count}/${st.lastProvenance}${if (st.sawNonReal) "!NONREAL" else ""}"
        })
    }

    private fun start() {
        ContextCompat.startForegroundService(this, Intent(this, SensorHubService::class.java))
    }
}



