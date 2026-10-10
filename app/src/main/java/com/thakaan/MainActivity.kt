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
import com.thakaan.ui.DiagnosticsScreen
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
        setContent {
            var running by remember { mutableStateOf(false) }
            val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                // Start whatever the grants allow; the service skips location if it was refused.
                start()
                ShiftRuntime.start(applicationContext, lifecycleScope)
                running = true
            }
            var recording by remember { mutableStateOf(false) }
            var replaying by remember { mutableStateOf(false) }
            val sessionFile = remember { File(filesDir, "session.thkr") }
            val recorder = remember { StreamRecorder(sessionFile) }
            var diagnostics by remember { mutableStateOf(false) }
            var chhaya by remember { mutableStateOf(false) }
            var safety by remember { mutableStateOf(false) }
            if (chhaya) { ChhayaScreen(onBack = { chhaya = false }); return@setContent }
            if (safety) { SafetyScreen(onBack = { safety = false }); return@setContent }
            if (diagnostics) {
                DiagnosticsScreen(onBack = { diagnostics = false })
                return@setContent
            }
            HomeScreen(
                onOpenDiagnostics = { diagnostics = true },
                onOpenChhaya = { chhaya = true },
                onOpenSafety = { safety = true },
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
                        ShiftRuntime.start(applicationContext, lifecycleScope, sessionFile.lastModified() / 1000 - 600)
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



