package com.thakaan.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.core.Provenance
import com.thakaan.risk.Level
import com.thakaan.risk.mockLevelFor
import com.thakaan.shift.ShiftRuntime
import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.Stream

enum class Mode(val label: String) { RIDER("Rider"), SITE("Site") }

/** High-contrast light theme: readable in direct sun. */
private val SunScheme = lightColorScheme(
    primary = Color(0xFF003B73),
    onPrimary = Color.White,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
)

@Composable
fun HomeScreen(
    running: Boolean,
    recording: Boolean,
    replaying: Boolean,
    onToggleHub: () -> Unit,
    onToggleRecord: () -> Unit,
    onReplay: (Float) -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenChhaya: () -> Unit = {},
    onOpenSafety: () -> Unit = {},
) {
    MaterialTheme(colorScheme = SunScheme) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val stats by SensorHub.stats.collectAsStateWithLifecycle()
            val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            var mode by remember { mutableStateOf(Mode.RIDER) }
            var mockTss by remember { mutableFloatStateOf(18f) }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Mode.entries.forEach { m ->
                        FilterChip(selected = mode == m, onClick = { mode = m }, label = { Text(m.label, fontSize = 18.sp) })
                    }
                }

                val s = snap
                if (s != null) {
                    Gauge(tss = s.tss, level = s.level)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { ProvenanceChip(s.provenance) }
                    if (s.collapseSecondsLeft > 0 || s.collapseAlarm) {
                        Text(
                            if (s.collapseAlarm) "Alarm: help is being called" else "Are you OK? Say haan or tap. ${s.collapseSecondsLeft}s",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color(0xFF9B0000),
                        )
                    }
                    s.forecastHeadline?.let { Text(it, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    s.drivers.take(3).forEach { Text("- $it", fontSize = 18.sp) }
                    Text(
                        "Sun %.0f min   Rest %.0f min ago   Water deficit %s%s".format(s.sunDoseMin, s.minutesSinceRest, s.hydrationText, s.wbgtC?.let { "   Heat index %.0f".format(it) } ?: "   Weather unknown (counted as hot)"),
                        fontSize = 14.sp, color = Color.DarkGray,
                    )
                } else {
                    Gauge(tss = mockTss.toInt())
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        // Until sensing starts the gauge is a demo dial, so it is labelled SIMULATED.
                        ProvenanceChip(Provenance.SIMULATED)
                    }
                    Text("Start sensing to see your real level. This dial is a demo.", fontSize = 14.sp)
                    Slider(value = mockTss, onValueChange = { mockTss = it }, valueRange = 0f..100f)
                }
                Button(onClick = onToggleHub, Modifier.fillMaxWidth()) {
                    Text(if (running) "Stop sensing" else "Start sensing", fontSize = 20.sp)
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onToggleRecord, enabled = running && !replaying) {
                        Text(if (recording) "Stop rec" else "Record", fontSize = 16.sp)
                    }
                    // Replay needs live sensing off so REPLAY and REAL never share a stream.
                    Button(onClick = { onReplay(1f) }, enabled = !running && !recording && !replaying) { Text("Replay 1x", fontSize = 16.sp) }
                    Button(onClick = { onReplay(20f) }, enabled = !running && !recording && !replaying) { Text("Replay 20x", fontSize = 16.sp) }
                }

                val ctx = androidx.compose.ui.platform.LocalContext.current
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { copyToClipboard(ctx, "Thakaan status", ShiftRuntime.crewStatusSealed("Me", null, true)) }, enabled = snap != null) { Text("Copy status for console", fontSize = 14.sp) }
                    Button(onClick = { copyToClipboard(ctx, "Thakaan exposure log", ShiftRuntime.exportChain()) }, enabled = snap != null) { Text("Copy exposure log", fontSize = 14.sp) }
                }
                Button(onClick = onOpenChhaya, Modifier.fillMaxWidth()) { Text("Chhaya route (shade and water)", fontSize = 20.sp) }
                Button(onClick = onOpenSafety, Modifier.fillMaxWidth()) { Text("Safety checks", fontSize = 18.sp) }
                Button(onClick = onOpenDiagnostics, Modifier.fillMaxWidth()) { Text("Diagnostics", fontSize = 18.sp) }

                Text("Sensor streams (${mode.label} mode)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Stream.entries.forEach { s ->
                    val st = stats[s]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, Color.Gray, RoundedCornerShape(8.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            Text(s.label, fontSize = 16.sp)
                            Text(
                                if (st == null) "no data" else "%d readings, %.1f Hz".format(st.count, st.ratePerSec),
                                fontSize = 14.sp, color = Color.DarkGray,
                            )
                        }
                        st?.lastProvenance?.let { ProvenanceChip(it) }
                    }
                }
            }
        }
    }
}

@Composable
fun ProvenanceChip(p: Provenance) {
    val bg = when (p) {
        Provenance.REAL -> Color(0xFF1B7F3B)
        Provenance.REPLAY -> Color(0xFF6A1B9A)
        Provenance.SIMULATED -> Color(0xFF455A64)
    }
    Box(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(p.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun Gauge(tss: Int, level: Level = mockLevelFor(tss)) {
    Box(Modifier.fillMaxWidth().height(240.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(240.dp)) {
            val stroke = 28.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0xFFE0E0E0), 135f, 270f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(level.color, 135f, 270f * tss / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${level.shape} ${level.word}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = level.color)
            Text("Strain $tss", fontSize = 20.sp)
        }
    }
}

private fun copyToClipboard(ctx: android.content.Context, label: String, text: String?) {
    if (text == null) return
    val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
}

