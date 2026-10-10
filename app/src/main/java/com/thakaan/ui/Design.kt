package com.thakaan.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thakaan.risk.Level
import kotlin.math.cos
import kotlin.math.sin

/**
 * The look of the app: a calm field instrument that stays readable in direct sun. Warm off-white ground, deep navy ink, one
 * navy accent, white cards with a thin warm border. Level colour is never the only cue: every level also has a shape and a word.
 * Text is dark on light everywhere (high contrast), numbers use tabular figures so they do not jitter as they update.
 */
object Ink {
    val ground = Color(0xFFF5F2EC)
    val card = Color.White
    val ink = Color(0xFF14202B)
    val sub = Color(0xFF4A5864)
    val line = Color(0xFFE2DDD3)
    val accent = Color(0xFF0F3D63)
    val accentSoft = Color(0xFFE3EDF6)
    val warnBg = Color(0xFFFFF4E0)
    val warnInk = Color(0xFF8A5A00)
    val alertBg = Color(0xFFFCE8E6)
    val alertInk = Color(0xFF9B0000)
}

private val ThakaanColors = lightColorScheme(
    primary = Ink.accent, onPrimary = Color.White,
    primaryContainer = Ink.accentSoft, onPrimaryContainer = Ink.accent,
    secondaryContainer = Ink.accentSoft, onSecondaryContainer = Ink.accent,
    background = Ink.ground, onBackground = Ink.ink,
    surface = Ink.card, onSurface = Ink.ink, onSurfaceVariant = Ink.sub,
    surfaceVariant = Ink.accentSoft, outline = Ink.line, outlineVariant = Ink.line,
)

private val ThakaanType = Typography(
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 17.sp),
    bodyMedium = TextStyle(fontSize = 15.sp),
    labelLarge = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
)

private val ThakaanShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp),
)

@Composable
fun ThakaanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ThakaanColors, typography = ThakaanType, shapes = ThakaanShapes, content = content)
}

/** Tabular figures: digits keep their width, so a counter does not shift the text next to it. */
val Tabular = TextStyle(fontFeatureSettings = "tnum")

/** White card with a thin warm border. Radius 20 so a 12-radius child with 8 padding nests concentrically. */
@Composable
fun AppCard(modifier: Modifier = Modifier, tint: Color = Ink.card, border: Color = Ink.line, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    val base = modifier.fillMaxWidth().background(tint, shape).let { m -> m.then(Modifier.border(BorderStroke(1.dp, border), shape)) }
    val clickable = if (onClick != null) base.clickable(onClick = onClick) else base
    Column(clickable.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun SectionLabel(text: String) {
    Text(text.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink.sub, letterSpacing = 1.2.sp, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
}

/** One number with a label under it. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, note: String? = null) {
    Column(
        modifier.background(Ink.card, RoundedCornerShape(16.dp)).then(Modifier.border(BorderStroke(1.dp, Ink.line), RoundedCornerShape(16.dp))).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, fontSize = 13.sp, color = Ink.sub, fontWeight = FontWeight.Medium)
        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink.ink, style = Tabular)
        if (note != null) Text(note, fontSize = 12.sp, color = Ink.sub)
    }
}

/** Filled button in the accent colour, at least 52 dp tall (a gloved or sweaty thumb needs the room). */
@Composable
fun PrimaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val bg = if (!enabled) Color(0xFFD9D5CC) else if (danger) Ink.alertInk else Ink.accent
    Box(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 56.dp).background(bg, RoundedCornerShape(16.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Color.White else Ink.sub, fontSize = 19.sp, fontWeight = FontWeight.Bold) }
}

/** Outlined button for the second choice on a screen. */
@Composable
fun SecondaryAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp).background(Ink.card, RoundedCornerShape(16.dp))
            .then(Modifier.border(BorderStroke(1.5.dp, if (enabled) Ink.accent else Ink.line), RoundedCornerShape(16.dp)))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) Ink.accent else Ink.sub, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

/** A row in a settings list: title, optional second line, chevron. */
@Composable
fun ListRow(title: String, subtitle: String? = null, onClick: () -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().defaultMinSize(minHeight = 60.dp).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) Ink.ink else Ink.sub)
            if (subtitle != null) Text(subtitle, fontSize = 13.sp, color = Ink.sub)
        }
        Canvas(Modifier.size(14.dp)) {
            val p = Path().apply { moveTo(size.width * 0.3f, size.height * 0.1f); lineTo(size.width * 0.8f, size.height * 0.5f); lineTo(size.width * 0.3f, size.height * 0.9f) }
            drawPath(p, Ink.sub, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

enum class Glyph { TODAY, CHECK, ROUTE, REPORT, GEAR, BACK }

/** Line icons drawn in code (the project carries no icon library). 24 dp box, 2 dp strokes, round caps. */
@Composable
fun GlyphIcon(g: Glyph, tint: Color, modifier: Modifier = Modifier.size(24.dp)) {
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val st = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (g) {
            Glyph.TODAY -> { // a gauge arc with a needle
                drawArc(tint, 150f, 240f, false, Offset(w * 0.1f, h * 0.12f), Size(w * 0.8f, h * 0.8f), style = st)
                drawLine(tint, Offset(w * 0.5f, h * 0.52f), Offset(w * 0.7f, h * 0.32f), st.width, StrokeCap.Round)
            }
            Glyph.CHECK -> { // a pulse line
                val p = Path().apply { moveTo(w * 0.05f, h * 0.55f); lineTo(w * 0.3f, h * 0.55f); lineTo(w * 0.42f, h * 0.2f); lineTo(w * 0.6f, h * 0.85f); lineTo(w * 0.72f, h * 0.55f); lineTo(w * 0.95f, h * 0.55f) }
                drawPath(p, tint, style = st)
            }
            Glyph.ROUTE -> { // a winding path ending in a pin
                val p = Path().apply { moveTo(w * 0.15f, h * 0.85f); cubicTo(w * 0.15f, h * 0.55f, w * 0.55f, h * 0.7f, w * 0.55f, h * 0.45f) }
                drawPath(p, tint, style = st)
                drawCircle(tint, w * 0.13f, Offset(w * 0.7f, h * 0.28f), style = st)
            }
            Glyph.REPORT -> { // three bars
                drawLine(tint, Offset(w * 0.2f, h * 0.85f), Offset(w * 0.2f, h * 0.5f), st.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.5f, h * 0.85f), Offset(w * 0.5f, h * 0.2f), st.width, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.8f, h * 0.85f), Offset(w * 0.8f, h * 0.65f), st.width, StrokeCap.Round)
            }
            Glyph.GEAR -> {
                drawCircle(tint, w * 0.17f, Offset(w / 2, h / 2), style = st)
                for (i in 0 until 8) {
                    val a = Math.toRadians(i * 45.0)
                    drawLine(tint, Offset(w / 2 + (w * 0.30f * cos(a)).toFloat(), h / 2 + (h * 0.30f * sin(a)).toFloat()), Offset(w / 2 + (w * 0.42f * cos(a)).toFloat(), h / 2 + (h * 0.42f * sin(a)).toFloat()), st.width, StrokeCap.Round)
                }
                drawCircle(tint, w * 0.30f, Offset(w / 2, h / 2), style = st)
            }
            Glyph.BACK -> {
                val p = Path().apply { moveTo(w * 0.62f, h * 0.15f); lineTo(w * 0.25f, h * 0.5f); lineTo(w * 0.62f, h * 0.85f) }
                drawPath(p, tint, style = st)
            }
        }
    }
}

/** Round icon button with a 48 dp hit area. */
@Composable
fun IconAction(g: Glyph, description: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { GlyphIcon(g, Ink.ink) }
}

/** Top bar for pushed screens: back arrow and a title. */
@Composable
fun PageHeader(title: String, onBack: (() -> Unit)?, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (onBack != null) IconAction(Glyph.BACK, "Back", onBack)
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink.ink, modifier = Modifier.weight(1f).padding(start = if (onBack == null) 4.dp else 0.dp))
        trailing()
    }
}

/**
 * The strain gauge: a track, a level-coloured arc that eases to its new value, and the level written as shape + word
 * (colour is never the only cue). [idle] draws an empty track and a calm "Ready" for when nothing is being sensed.
 */
@Composable
fun StrainGauge(tss: Int, level: Level, idle: Boolean = false, modifier: Modifier = Modifier) {
    val sweep by animateFloatAsState(if (idle) 0f else 270f * tss.coerceIn(0, 100) / 100f, tween(600), label = "gauge")
    Box(modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.TopCenter) {
      Box(Modifier.size(230.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(230.dp)) {
            val stroke = 26.dp.toPx(); val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(Color(0x1F14202B), 135f, 270f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (sweep > 0.5f) drawArc(level.color, 135f, sweep, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (idle) {
                Text("Ready", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Ink.sub)
                Text("not sensing", fontSize = 16.sp, color = Ink.sub)
            } else {
                Text("${level.shape} ${level.word}", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = level.color)
                Text("Strain $tss", fontSize = 20.sp, color = Ink.ink, style = Tabular, fontWeight = FontWeight.Medium)
            }
        }
      }
    }
}
