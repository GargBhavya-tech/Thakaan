package com.thakaan.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.sensorhub.SensorHub
import com.thakaan.sensorhub.Stream
import com.thakaan.shift.ShiftRuntime

/**
 * Settings: everything that is for the team, the demo or the engineers rather than for the worker. Recording and replaying a
 * shift (replay needs live sensing off, so REPLAY and REAL never share a stream), the console and log copy buttons, the safety
 * checks, diagnostics (the labs for chest, pulse, speech and models are inside it), the face live view, and the raw sensor streams.
 */
@Composable
fun SettingsScreen(
    running: Boolean,
    recording: Boolean,
    replaying: Boolean,
    workType: com.thakaan.settings.WorkType,
    onWorkType: (com.thakaan.settings.WorkType) -> Unit,
    onToggleRecord: () -> Unit,
    onReplay: (Float) -> Unit,
    onOpenSafety: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenFaceLive: () -> Unit,
    onBack: () -> Unit,
) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ctx = LocalContext.current
            val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            val stats by SensorHub.stats.collectAsStateWithLifecycle()
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PageHeader("Settings", onBack)

                SectionLabel("Your work")
                AppCard {
                    Text("This sets which checks the Quick check asks for. Gloves, helmets and noise make some checks unreliable, so each kind of work gets its own.", fontSize = 14.sp, color = Ink.sub)
                    com.thakaan.settings.WorkType.entries.forEach { w ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onWorkType(w) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            RadioButton(selected = workType == w, onClick = null)
                            Column(Modifier.weight(1f)) {
                                Text(w.label, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(w.hint, fontSize = 13.sp, color = Ink.sub)
                            }
                        }
                    }
                }

                SectionLabel("Demo tools")
                AppCard {
                    Text("Record a shift while sensing, then replay it with sensing off. A replay is always labelled REPLAY.", fontSize = 14.sp, color = Ink.sub)
                    SecondaryAction(if (recording) "Stop recording" else "Record this shift", onClick = onToggleRecord, enabled = running && !replaying)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SecondaryAction("Replay 1x", onClick = { onReplay(1f) }, modifier = Modifier.weight(1f), enabled = !running && !recording && !replaying)
                        SecondaryAction("Replay 20x", onClick = { onReplay(20f) }, modifier = Modifier.weight(1f), enabled = !running && !recording && !replaying)
                    }
                    if (running) Text("Stop sensing on Today to replay.", fontSize = 13.sp, color = Ink.warnInk)
                }

                SectionLabel("Share")
                AppCard {
                    SecondaryAction("Copy status for console", onClick = { copy(ctx, "Thakaan status", ShiftRuntime.crewStatusSealed("Me", null, true)) }, enabled = snap != null)
                    SecondaryAction("Copy exposure log", onClick = { copy(ctx, "Thakaan exposure log", ShiftRuntime.exportChain()) }, enabled = snap != null)
                    Text("The console status holds the level and coarse reasons only. The exposure log is signed and hash-chained.", fontSize = 13.sp, color = Ink.sub)
                }

                SectionLabel("Checks and labs")
                AppCard {
                    ListRow("Safety checks", "Six checks that the level never drops when information is lost", onOpenSafety)
                    ListRow("Diagnostics", "Models, sensors, device self-tests, chest, pulse and speech labs", onOpenDiagnostics)
                    ListRow("Face live view", "Pulse, breathing and oxygen estimates from the front camera (experimental)", onOpenFaceLive)
                }

                SectionLabel("Sensor streams")
                AppCard {
                    Stream.entries.forEach { s ->
                        val st = stats[s]
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f)) {
                                Text(s.label, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                                Text(if (st == null) "no data" else "%d readings, %.1f Hz".format(st.count, st.ratePerSec), fontSize = 13.sp, color = Ink.sub, style = Tabular)
                            }
                            st?.lastProvenance?.let { ProvenanceChip(it) }
                        }
                    }
                }

                SectionLabel("About")
                Text(
                    "Thakaan is a heat-strain wellbeing tool, not a medical device. It works on this phone without a network. Shade is computed from map data and satellite heights, not measured. © OpenStreetMap contributors.",
                    fontSize = 13.sp, color = Ink.sub, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }
    }
}

private fun copy(ctx: Context, label: String, text: String?) {
    if (text == null) return
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
}
