package com.thakaan.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.chhaya.ChhayaRouter
import com.thakaan.chhaya.PoiType
import com.thakaan.chhaya.RoadGraph
import com.thakaan.chhaya.ShadeLayer
import com.thakaan.core.Provenance
import com.thakaan.risk.Level
import com.thakaan.shift.ShiftRuntime
import java.util.Calendar

private class ChhayaData(val graph: RoadGraph, val shade: ShadeLayer, val router: ChhayaRouter)

private var cached: ChhayaData? = null

private fun load(ctx: Context): ChhayaData? = cached ?: try {
    val g = ctx.assets.open("chhaya/graph.json").bufferedReader().use { RoadGraph(it.readText()) }
    val s = ctx.assets.open("chhaya/shade.json").bufferedReader().use { ShadeLayer(it.readText()) }
    ChhayaData(g, s, ChhayaRouter(g, s, com.thakaan.chhaya.FloodStore.get(ctx))).also { cached = it }
} catch (_: Exception) { null }

/**
 * Chhaya map (#49): roads coloured by sun (orange) versus shade (green), the route in blue, water and shade stops, and
 * one chip with the extra time and shade share. Fully offline. DEVICE-UNTESTED.
 */
@Composable
fun ChhayaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val data = remember { load(ctx) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            if (data == null) {
                Column(Modifier.padding(16.dp)) { Button(onClick = onBack) { Text("Back") }; Text("Map data is missing from this build.", fontSize = 20.sp) }
                return@Surface
            }
            val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            val live = snap
            var demoLevel by remember { mutableStateOf(Level.WARMING) }
            val level = live?.level ?: demoLevel
            val tss = live?.tss ?: when (demoLevel) { Level.COOL -> 10; Level.WARMING -> 42; Level.STRAINED -> 65; Level.DANGER -> 90 }
            val g = data.graph
            val pos = ShiftRuntime.engine?.position
            var origin by remember { mutableStateOf(pos ?: (12.9655 to 77.5900)) }
            var dest by remember { mutableStateOf(12.9850 to 77.6100) }
            val minute = remember { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
            var floodVersion by remember { mutableStateOf(0) }
            val floods = remember { com.thakaan.chhaya.FloodStore.get(ctx) }
            val route = remember(level, tss, origin, dest, floodVersion) { data.router.route(origin.first, origin.second, dest.first, dest.second, level, tss, minute) }
            val bb = g.nodeLat.min() to g.nodeLat.max()
            val lonMin = g.nodeLon.min(); val lonMax = g.nodeLon.max()

            Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onBack) { Text("Back", fontSize = 16.sp) }
                    if (live == null) Level.entries.forEach { l -> FilterChip(selected = demoLevel == l, onClick = { demoLevel = l }, label = { Text(l.word, fontSize = 14.sp) }) }
                    else ProvenanceChip(live.provenance)
                }
                if (live == null) Text("No live level yet: the level buttons are a demo (SIMULATED).", fontSize = 12.sp)
                Canvas(
                    Modifier.fillMaxWidth().weight(1f).pointerInput(g) {
                        detectTapGestures { p ->
                            val lat = bb.second - (p.y / size.height) * (bb.second - bb.first)
                            val lon = lonMin + (p.x / size.width) * (lonMax - lonMin)
                            dest = lat to lon
                        }
                    },
                ) {
                    fun x(lon: Double) = ((lon - lonMin) / (lonMax - lonMin) * size.width).toFloat()
                    fun y(lat: Double) = ((bb.second - lat) / (bb.second - bb.first) * size.height).toFloat()
                    for (e in 0 until g.edgeCount) {
                        val geom = g.edgeGeom[e]
                        val sun = data.shade.sunFraction(e, minute).toFloat()
                        val c = lerp(Color(0xFF2E7D32), Color(0xFFE65100), sun.coerceIn(0f, 1f))
                        for (i in 0 until geom.size - 2 step 2) {
                            drawLine(c, Offset(x(geom[i + 1] / 1e6), y(geom[i] / 1e6)), Offset(x(geom[i + 3] / 1e6), y(geom[i + 2] / 1e6)), strokeWidth = 2f)
                        }
                    }
                    floods.active(System.currentTimeMillis()).forEach { fr ->
                        val geom = g.edgeGeom[fr.edge]
                        for (i in 0 until geom.size - 2 step 2) {
                            drawLine(Color(0xFFD50000), Offset(x(geom[i + 1] / 1e6), y(geom[i] / 1e6)), Offset(x(geom[i + 3] / 1e6), y(geom[i + 2] / 1e6)), strokeWidth = 8f)
                        }
                    }
                    route?.arcs?.forEach { a ->
                        val pts = g.arcPolyline(a)
                        for (i in 0 until pts.size - 1) {
                            drawLine(Color(0xFF0D47A1), Offset(x(pts[i].second), y(pts[i].first)), Offset(x(pts[i + 1].second), y(pts[i + 1].first)), strokeWidth = 9f, cap = StrokeCap.Round)
                        }
                    }
                    g.pois.filter { it.type == PoiType.WATER || it.type == PoiType.SHELTER }.forEach { p ->
                        drawCircle(if (p.type == PoiType.WATER) Color(0xFF00B0FF) else Color(0xFF6A1B9A), 8f, Offset(x(p.lon), y(p.lat)))
                    }
                    route?.stop?.let { drawCircle(Color.Black, 14f, Offset(x(it.lon), y(it.lat)), style = Stroke(5f)) }
                    drawCircle(Color.Black, 12f, Offset(x(origin.second), y(origin.first)))
                    drawCircle(Color(0xFFC62828), 14f, Offset(x(dest.second), y(dest.first)))
                }
                if (route == null) {
                    Text("No route found between these points.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text("${route.chip}", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0D47A1))
                    Text(route.note, fontSize = 18.sp)
                    Text("%.1f km, %.0f min".format(route.distanceM / 1000.0, route.timeSec / 60.0), fontSize = 16.sp)
                    Text("Shade is computed from OSM buildings and trees, not measured. %.0f%% of this route rests on guessed building heights.".format(route.lowConfidenceShare * 100), fontSize = 12.sp, color = Color.DarkGray)
                }
                FloodReportRow(g, floods, pos ?: origin, ctx) { floodVersion += 1 }
                Text("Orange = sun, green = shade. Blue dots: water (listed, quality unverified). Purple: shelter. Red lines: roads reported flooded. Tap to set the destination.", fontSize = 12.sp)
                Text("© OpenStreetMap contributors", fontSize = 12.sp)
            }
        }
    }
}
