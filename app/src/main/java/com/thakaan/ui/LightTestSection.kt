package com.thakaan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thakaan.exposure.LightClassifier
import com.thakaan.exposure.LightEstimate
import com.thakaan.exposure.LightInput
import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.Stream
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Light-sensor test (#16): live lux and the verdict the app's classifier gives, plus an 8-second capture per situation
 * (sun, shade, pocket or covered) whose summary stays on screen, so the phone can be carried outside and read afterwards.
 * Sensing must be running. It uses the same gravity low-pass and classifier as the engine; position is the venue default.
 */
@Composable
fun LightTestSection() {
    val results = remember { mutableStateListOf<String>() }
    val samples = remember { ArrayList<Pair<Double, LightEstimate>>() }
    var live by remember { mutableStateOf("waiting for the light sensor (press Start sensing on Home first)") }
    var capturing by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        var gx = 0.0; var gy = 0.0; var gz = 0.0; var haveG = false
        SensorHub.readings.collect { r ->
            when (r.stream) {
                Stream.ACCEL -> {
                    val x = r.values[0].toDouble(); val y = r.values[1].toDouble(); val z = r.values[2].toDouble()
                    if (!haveG) { gx = x; gy = y; gz = z; haveG = true } else { gx = 0.98 * gx + 0.02 * x; gy = 0.98 * gy + 0.02 * y; gz = 0.98 * gz + 0.02 * z }
                }
                Stream.LIGHT -> {
                    val lux = r.values[0].toDouble()
                    val est = LightClassifier.classifyInstant(
                        LightInput(r.tNanos, System.currentTimeMillis() / 1000, 12.97, 77.59, lux, null, if (haveG) doubleArrayOf(gx, gy, gz) else null, null, null, null, null, r.provenance),
                    )
                    live = "%.0f lux: %s (counted as sun: %s). %s".format(lux, est.state, if (est.effectiveSun) "yes" else "no", est.reason)
                    if (capturing != null) samples += lux to est
                }
                else -> {}
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(live, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Sun", "Shade", "Covered").forEach { label ->
                Button(enabled = capturing == null, onClick = {
                    samples.clear(); capturing = label
                    scope.launch {
                        delay(8000)
                        val s = samples.toList()
                        capturing = null
                        results += if (s.isEmpty()) "$label: no light readings (is sensing on?)" else {
                            val lux = s.map { it.first }.sorted()
                            val counts = s.groupingBy { it.second.state }.eachCount().entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value * 100 / s.size}%" }
                            val sun = s.count { it.second.effectiveSun } * 100 / s.size
                            "$label: lux median %.0f (min %.0f, max %.0f); %s; counted as sun %d%%".format(lux[lux.size / 2], lux.first(), lux.last(), counts, sun)
                        }
                    }
                }) { Text(label, fontSize = 14.sp) }
            }
        }
        if (capturing != null) Text("Capturing ${capturing}: hold the phone in that situation for 8 seconds...", fontSize = 15.sp)
        results.forEach { Text(it, Modifier.fillMaxWidth(), fontSize = 14.sp) }
    }
}
