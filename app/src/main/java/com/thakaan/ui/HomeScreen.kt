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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.thakaan.shift.CheckSchedule
import com.thakaan.risk.Outlook
import com.thakaan.risk.OutlookBand
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.graphics.compositeOver
import kotlinx.coroutines.delay

/**
 * Today: the one screen a worker looks at. The level is the hero (colour, shape and word), then the one-line reason, then
 * four numbers, then the quick check. Everything for developers and demos (record, replay, diagnostics, labs, raw sensor
 * streams) lives in Settings. Until sensing starts the dial is empty and says so; it no longer shows a made-up level.
 */
@Composable
fun HomeScreen(
    running: Boolean,
    replaying: Boolean,
    workType: com.thakaan.settings.WorkType,
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
                        val noPulse = "no fresh heart reading"
                        val reasons = s.drivers.filter { !it.equals(noPulse, true) }
                        val headline = (s.forecastHeadline ?: AlertLadder.plan(level, Lang.EN).bannerText ?: reasons.firstOrNull() ?: if (s.drivers.any { it.equals(noPulse, true) }) "Do a quick check to read your pulse" else "Holding steady").replaceFirstChar { it.uppercase() }
                        Text(headline, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
                        reasons.filter { it.replaceFirstChar { c -> c.uppercase() } != headline }.take(3).forEach { Text("•  $it", fontSize = 15.sp, color = Ink.sub) }
                    } else {
                        Text(if (replaying) "Replay is running." else "Your level appears here once sensing starts.", fontSize = 17.sp, color = Ink.sub)
                    }
                }

                if (running) SecondaryAction("Stop sensing", onClick = onToggleHub)
                else PrimaryAction("Start sensing", onClick = onToggleHub, enabled = !replaying)

                if (s != null) {
                    s.outlook?.let { OutlookCard(it, s.level) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Sun today", "%.0f min".format(s.sunDoseMin), Modifier.weight(1f))
                        StatTile("Since rest", "%.0f min".format(s.minutesSinceRest), Modifier.weight(1f))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile("Water", waterText(s.hydrationText), Modifier.weight(1f), note = "fluid deficit")
                        StatTile("Heat index", s.wbgtC?.let { "%.0f".format(it) } ?: "unknown", Modifier.weight(1f), note = if (s.wbgtC == null) "counted as hot" else "weather and sun")
                    }
                    if (s.airDoseText.isNotEmpty()) AppCard {
                        Text("Air dose today", fontSize = 13.sp, color = Ink.sub, fontWeight = FontWeight.Medium)
                        Text(s.airDoseText, fontSize = 15.sp, color = Ink.ink)
                    }
                    s.rainCue?.let { cue -> AppCard(tint = Ink.accentSoft, border = Ink.accent) { Text(cue, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Ink.accent) } }
                }

                if (!workType.rides) {
                    if (s != null) CheckDueCard(s.level, workType, onOpenQuickCheck)
                    else AppCard {
                        Text("Quick check", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                        Text(quickCheckBlurb(workType), fontSize = 15.sp, color = Ink.sub)
                        PrimaryAction("Start quick check", onClick = onOpenQuickCheck)
                    }
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

/** The next 20 minutes: the chance of reaching Strained, what is pushing it, and the one thing to do. */
@Composable
private fun OutlookCard(o: Outlook, level: Level) {
    val color = when (o.band) { OutlookBand.LOW -> Level.COOL.color; OutlookBand.MODERATE -> Level.WARMING.color; OutlookBand.HIGH -> Level.STRAINED.color }
    val already = level >= Level.STRAINED
    AppCard(tint = color.copy(alpha = 0.07f).compositeOver(Color.White), border = color.copy(alpha = 0.5f).compositeOver(Ink.line)) {
        Text("NEXT ${o.horizonMin} MINUTES", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink.sub, letterSpacing = 1.2.sp)
        if (!already) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${o.strainedPct}%", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = color, style = Tabular)
            Column {
                Text("chance of reaching Strained", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                Text("${o.band.word} risk", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = color)
            }
        }
        Text(o.advice, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink.ink)
        o.reasons.forEach { Text("•  $it", fontSize = 14.sp, color = Ink.sub) }
    }
}

/** When the next check is due, from the last check and the level (hotter means sooner). Due now turns amber. */
@Composable
private fun CheckDueCard(level: Level, workType: com.thakaan.settings.WorkType, onCheck: () -> Unit) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(Unit) { while (true) { delay(15_000); nowSec = System.currentTimeMillis() / 1000 } }
    val due = CheckSchedule.dueInSec(ShiftRuntime.lastCheckEpochSec, nowSec, level)
    if (due <= 0) AppCard(tint = Ink.warnBg, border = Ink.warnInk) {
        Text("Check due now", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.warnInk)
        Text(quickCheckBlurb(workType), fontSize = 15.sp, color = Ink.ink)
        PrimaryAction("Start check", onClick = onCheck)
    } else AppCard {
        Text(CheckSchedule.dueText(due), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(quickCheckBlurb(workType), fontSize = 15.sp, color = Ink.sub)
        SecondaryAction("Check now", onClick = onCheck)
    }
}
