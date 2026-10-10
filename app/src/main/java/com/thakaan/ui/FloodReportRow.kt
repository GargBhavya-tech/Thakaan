package com.thakaan.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.thakaan.chhaya.FloodPhotoCheck
import com.thakaan.chhaya.FloodReports
import com.thakaan.chhaya.FloodStore
import com.thakaan.chhaya.RoadGraph
import com.thakaan.face.FrontCameraCapture
import kotlin.math.sqrt

/**
 * One-tap flood report (#59): the rear camera takes a fresh frame, [FloodPhotoCheck] judges it from numbers (not too old, not
 * dark, not blank), the frame is dropped and never saved (invariant 7), and the road nearest to [where] is closed for routing
 * for three hours. The result is worded "reported flooded", never as a depth or as confirmed.
 */
@Composable
fun FloodReportRow(g: RoadGraph, floods: FloodReports, where: Pair<Double, Double>, ctx: Context, onChanged: () -> Unit) {
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val active = floods.active(System.currentTimeMillis()).size
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = {
                if (!granted) { ask.launch(Manifest.permission.CAMERA); return@Button }
                busy = true; message = "Point the camera at the water. Taking the photo..."
                val tapAt = SystemClock.elapsedRealtime()
                val cam = FrontCameraCapture(ctx)
                var frames = 0
                cam.start(
                    onFrame = { _, _, px ->
                        // about 0.7 s in, exposure has settled: judge this frame, then drop it
                        if (++frames == 20) {
                            var sum = 0.0; var sq = 0.0
                            for (c in px) { val l = 0.299 * ((c shr 16) and 0xFF) + 0.587 * ((c shr 8) and 0xFF) + 0.114 * (c and 0xFF); sum += l; sq += l * l }
                            val mean = sum / px.size; val std = sqrt((sq / px.size - mean * mean).coerceAtLeast(0.0))
                            cam.stop()
                            val problem = FloodPhotoCheck.problem(SystemClock.elapsedRealtime() - tapAt, mean, std)
                            val res = floods.report(g, where.first, where.second, System.currentTimeMillis(), problem)
                            FloodStore.save(ctx)
                            message = res.message; busy = false
                            if (res.accepted) { onChanged(); com.thakaan.mesh.CrewMesh.publishFlood(where.first, where.second) }
                        }
                    },
                    onError = { message = "Camera: $it"; busy = false },
                    rear = true,
                )
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (granted) "Report flood here (takes a photo)" else "Allow camera to report floods", fontSize = 16.sp) }
        if (message.isNotEmpty()) Text(message, fontSize = 14.sp)
        if (active > 0) {
            val crew = floods.active(System.currentTimeMillis()).count { it.fromCrew }
            Text("$active road(s) reported flooded${if (crew > 0) " ($crew from the crew mesh)" else ""}; routes avoid them.", fontSize = 12.sp, color = Color(0xFFB71C1C))
        }
    }
}
