package com.thakaan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.core.Provenance
import com.thakaan.risk.AlertLadder
import com.thakaan.risk.Lang
import com.thakaan.risk.Level
import com.thakaan.shift.ShiftRuntime

/**
 * Today: the one screen a worker looks at. The level is the hero (colour, shape and word), then the one-line reason, then
 * four numbers, then the quick check. Everything for developers and demos (record, replay, diagnostics, labs, raw sensor
 * streams) lives in Settings. Until sensing starts the dial is empty and says so; it no longer shows a made-up level.
 */
@Composable
fun HomeScreen(
    running: Boolean,
    replaying: Boolean,
    onToggleHub: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenQuickCheck: () -> Unit,
) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            val s = snap
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Thakaan", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.ink, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    if (s != null) ProvenanceChip(s.provenance)
                    IconAction(Glyph.GEAR, "Settings", onOpenSettings)
                }

                if (s != null && (s.collapseSecondsLeft > 0 || s.collapseAlarm)) {
                    AppCard(tint = Ink.alertBg, border = Ink.alertInk) {
                        Text(if (s.collapseAlarm) "Alarm: help is being called" else "Are you OK? Say haan or tap. ${s.collapseSecondsLeft} s", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.alertInk, style = Tabular)
                        PrimaryAction(if (s.collapseAlarm) "I'm OK, stop the alarm" else "I'm OK", onClick = { ShiftRuntime.engine?.resolveCollapse() }, danger = true)
                    }
                }

                if (replaying) AppCard(tint = Color(0xFFF1E6F7), border = Color(0xFF6A1B9A)) {
                    Text("Replaying a recorded shift. Nothing on this screen is live.", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF4A148C))
                }

                val level = s?.level ?: Level.COOL
                AppCard(tint = if (s != null) lerp(Color.White, level.color, 0.07f) else Ink.card, border = if (s != null) lerp(Ink.line, level.color, 0.45f) else Ink.line) {
                    StrainGauge(tss = s?.tss ?: 0, level = level, idle = s == null)
                    if (s != null) {
                        val headline = (s.forecastHeadline ?: AlertLadder.plan(level, Lang.EN).bannerText ?: s.drivers.firstOrNull() ?: "Holding steady").replaceFirstChar { it.uppercase() }
                        Text(headline, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                        s.drivers.filter { it.replaceFirstChar { c -> c.uppercase() } != headline }.take(3).forEach { Text("•  $it", fontSize = 15.sp, color = Ink.sub) }
                    } else {
                        Text(if (replaying) "Replay is running." else "Your level appears here once sensing starts.", fontSize = 17.sp, color = Ink.sub)
                    }
                }

                if (running) SecondaryAction("Stop sensing", onClick = onToggleHub)
                else PrimaryAction("Start sensing", onClick = onToggleHub, enabled = !replaying)

                if (s != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Sun today", "%.0f min".format(s.sunDoseMin), Modifier.weight(1f))
                        StatTile("Since rest", "%.0f min".format(s.minutesSinceRest), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Water", waterText(s.hydrationText), Modifier.weight(1f), note = "estimated deficit")
                        StatTile("Heat index", s.wbgtC?.let { "%.0f".format(it) } ?: "unknown", Modifier.weight(1f), note = if (s.wbgtC == null) "counted as hot" else "weather and sun")
                    }
                    if (s.airDoseText.isNotEmpty()) AppCard {
                        Text("Air dose today", fontSize = 13.sp, color = Ink.sub, fontWeight = FontWeight.Medium)
                        Text(s.airDoseText, fontSize = 15.sp, color = Ink.ink)
                    }
                    s.rainCue?.let { cue -> AppCard(tint = Ink.accentSoft, border = Ink.accent) { Text(cue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink.accent) } }
                }

                AppCard {
                    Text("Quick check", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("Pulse, steadiness, tapping and how you feel. About 95 seconds.", fontSize = 15.sp, color = Ink.sub)
                    PrimaryAction("Start quick check", onClick = onOpenQuickCheck)
                }

                Text("Heat-strain wellbeing tool, not a medical device. Levels are not temperatures.", fontSize = 12.sp, color = Ink.sub, modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp))
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
        Text(p.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** "0.0 to 0.0%" reads as noise: a range that rounds to one value is shown as that value, otherwise as a range. Never narrows the range. */
private fun waterText(raw: String): String {
    val m = Regex("""(-?\d+(?:\.\d+)?) to (-?\d+(?:\.\d+)?)%""").find(raw) ?: return raw.ifBlank { "unknown" }
    val lo = m.groupValues[1].toDouble(); val hi = m.groupValues[2].toDouble()
    return if (kotlin.math.abs(hi - lo) < 0.5) "%.0f%%".format(hi) else "%.0f to %.0f%%".format(lo, hi)
}
