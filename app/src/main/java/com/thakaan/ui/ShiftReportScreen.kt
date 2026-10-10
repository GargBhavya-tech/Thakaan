package com.thakaan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.core.Provenance
import com.thakaan.risk.Level
import com.thakaan.shift.ShiftRuntime

/**
 * Shift report: what the shift looked like, on the worker's own phone. Time at each level (colour, shape and words), the
 * highest level, when it changed, sun and rest, water, air dose, and how much of the data was real. The copy button hands out
 * levels only (invariant 7); the strain number, symptoms and air dose stay on the phone. Time with no readings is shown as
 * its own line, never as Cool time. Not a medical report.
 */
@Composable
fun ShiftReportScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
    val log = ShiftRuntime.log
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Shift report", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                if (log.isEmpty) {
                    Text("Nothing to report yet. Start sensing, and this fills in as the shift goes on.", fontSize = 18.sp)
                } else {
                    val total = log.coveredSec.coerceAtLeast(1.0)
                    Text("Covered %.0f min of readings".format(log.coveredSec / 60.0), fontSize = 18.sp)
                    Row(Modifier.fillMaxWidth().height(28.dp).background(Color(0xFFE0E0E0), RoundedCornerShape(6.dp))) {
                        Level.entries.forEach { l ->
                            val s = log.secondsAt(l)
                            if (s > 0) Box(Modifier.weight((s / total).toFloat().coerceAtLeast(0.01f)).height(28.dp).background(l.color))
                        }
                    }
                    Level.entries.forEach { l ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("${l.shape} ${l.word}", fontSize = 20.sp, color = l.color, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            Text("%.0f min".format(log.secondsAt(l) / 60.0), fontSize = 20.sp)
                        }
                    }
                    if (log.gapSec > 0) Text("No readings for %.0f min (the phone was off or not sensing). This time is not counted as Cool.".format(log.gapSec / 60.0), fontSize = 14.sp, color = Color(0xFF8A5A00))
                    Text("Highest level: ${log.peakLevel.shape} ${log.peakLevel.word}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = log.peakLevel.color)

                    val ch = log.changes()
                    if (ch.isNotEmpty()) {
                        Text("When it changed", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        ch.takeLast(12).forEach { Text("After %.0f min: ${it.from.word} to ${it.to.word}".format(it.atSec / 60.0), fontSize = 16.sp) }
                    }

                    snap?.let { s ->
                        Text("Right now", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text("Sun %.0f min. Rest %.0f min ago, rest owed %.0f min. Water deficit %s.".format(s.sunDoseMin, s.minutesSinceRest, s.restDebtMin, s.hydrationText), fontSize = 16.sp)
                        if (s.airDoseText.isNotEmpty()) Text("Air dose: ${s.airDoseText}", fontSize = 16.sp)
                    }
                    val symptoms = ShiftRuntime.engine?.symptoms?.size() ?: 0
                    if (symptoms > 0) Text("Symptom check-ins logged: $symptoms (kept on this phone)", fontSize = 16.sp)

                    val real = (log.shareFrom(Provenance.REAL) * 100).toInt()
                    val replay = (log.shareFrom(Provenance.REPLAY) * 100).toInt()
                    val sim = (log.shareFrom(Provenance.SIMULATED) * 100).toInt()
                    Text(
                        if (log.allReal) "Data: all real readings" else "Data: $real% real, $replay% replay, $sim% simulated. This report is not a real shift.",
                        fontSize = 14.sp, color = if (log.allReal) Color.DarkGray else Color(0xFF9B0000), fontWeight = if (log.allReal) FontWeight.Normal else FontWeight.Bold,
                    )
                    Button(onClick = { copy(ctx, log.levelsOnlySummary()) }, Modifier.fillMaxWidth()) { Text("Copy levels-only summary", fontSize = 18.sp) }
                    Text("The copy has minutes at each level and the highest level only: no strain number, symptoms or air dose.", fontSize = 12.sp, color = Color.DarkGray)
                }
                Text("Heat-strain wellbeing tool, not a medical device. Levels are not temperatures.", fontSize = 12.sp, color = Color.DarkGray)
                OutlinedButton(onClick = onBack, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
            }
        }
    }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Thakaan shift summary", text))
}
