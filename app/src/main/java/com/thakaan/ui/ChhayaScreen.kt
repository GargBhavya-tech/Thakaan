package com.thakaan.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.chhaya.ChhayaRouter
import com.thakaan.chhaya.PoiType
import com.thakaan.chhaya.RoadGraph
import com.thakaan.chhaya.ShadeLayer
import com.thakaan.risk.Level
import com.thakaan.shift.ShiftRuntime
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min

private class ChhayaData(val graph: RoadGraph, val shade: ShadeLayer, val router: ChhayaRouter)

/** Where the shade numbers come from. Satellite heights are the newer layer and stay opt-in until checked against real light. */
private enum class ShadeSource(val label: String) { MAP("Map data (OSM)"), SATELLITE("Satellite heights") }

private val cached = HashMap<ShadeSource, ChhayaData>()
private val SEASON_DAYS = intArrayOf(79, 172, 265, 355)

private fun load(ctx: Context, source: ShadeSource): ChhayaData? = cached[source] ?: try {
    val g = cached.values.firstOrNull()?.graph ?: com.thakaan.chhaya.RoadGraphStore.get(ctx) ?: throw IllegalStateException("no road graph")
    val file = if (source == ShadeSource.MAP) "chhaya/shade.json" else {
        val today = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        "chhaya/shade_obc_${SEASON_DAYS.minByOrNull { val d = kotlin.math.abs(it - today); minOf(d, 365 - d) }}.json"
    }
    val s = ctx.assets.open(file).bufferedReader().use { ShadeLayer(it.readText()) }
    ChhayaData(g, s, ChhayaRouter(g, s, com.thakaan.chhaya.FloodStore.get(ctx))).also { cached[source] = it }
} catch (_: Exception) { null }

/** What the map is currently explaining. Each layer says in words what its colours mean. */
private enum class MapLayer(val label: String) { SHADE("Shade"), WATER("Water"), FLOODS("Floods"), SIGNAL("Phone signal"), TRAFFIC("Traffic"), AIR("Air") }

private val SUN = Color(0xFFFF8F00)
private val PART_SHADE = Color(0xFFB8A21E)
private val SHADE_GREEN = Color(0xFF1B8E3E)
private val ROAD_GREY = Color(0xFFB8BEC6)
private val ROUTE_BLUE = Color(0xFF0D47A1)
private val WATER_BLUE = Color(0xFF0091EA)
private val SHELTER_PURPLE = Color(0xFF6A1B9A)
private val FLOOD_RED = Color(0xFFD50000)
private val SIGNAL_WEAK = Color(0xFFE6A100)

/**
 * Chhaya map (#49): one layer at a time, each with a legend in plain words. Roads are grey unless the chosen layer colours
 * them; the route is a thick blue line with a white edge; "You" and "Destination" are labelled pins; pinch to zoom, drag to
 * move, tap to set the destination. Fully offline. Shade is computed, not measured, and the screen says so.
 */
@Composable
fun ChhayaScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var source by remember { mutableStateOf(ShadeSource.MAP) }
    val data = remember(source) { load(ctx, source) }
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            if (data == null) {
                Column(Modifier.padding(16.dp)) { Button(onClick = onBack) { Text("Back") }; Text("Map data is missing from this build.", fontSize = 20.sp) }
                return@Surface
            }
            val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            val live = snap
            // null means "follow the live level"; a chosen level is a demo override and is marked SIMULATED
            var override by remember { mutableStateOf<Level?>(null) }
            val level = override ?: live?.level ?: Level.WARMING
            fun demoTss(l: Level) = when (l) { Level.COOL -> 10; Level.WARMING -> 42; Level.STRAINED -> 65; Level.DANGER -> 90 }
            val tss = if (override != null || live == null) demoTss(level) else live.tss
            val g = data.graph
            val pos = ShiftRuntime.engine?.position
            var origin by remember { mutableStateOf(pos ?: (12.9655 to 77.5900)) }
            var dest by remember { mutableStateOf(12.9850 to 77.6100) }
            val minute = remember { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
            var floodVersion by remember { mutableStateOf(0) }
            val floods = remember { com.thakaan.chhaya.FloodStore.get(ctx) }
            val crew by com.thakaan.mesh.CrewMesh.state.collectAsStateWithLifecycle()
            // flood reports heard over the crew mesh close roads here too (they can only add closures)
            androidx.compose.runtime.LaunchedEffect(crew.floodVersion, data) {
                var any = false
                val now = System.currentTimeMillis()
                com.thakaan.mesh.CrewMesh.drainFloods().forEach { if (floods.addCrew(data.graph, it.lat, it.lon, it.reportedAtSec * 1000, it.ttlMin, now)) any = true }
                if (any) { com.thakaan.chhaya.FloodStore.save(ctx); floodVersion += 1 }
            }
            val route = remember(source, level, tss, origin, dest, floodVersion) { data.router.route(origin.first, origin.second, dest.first, dest.second, level, tss, minute) }
            var layer by remember { mutableStateOf(MapLayer.SHADE) }
            var zoom by remember { mutableFloatStateOf(1f) }
            var pan by remember { mutableStateOf(Offset.Zero) }
            val measurer = rememberTextMeasurer()
            val rider = remember { com.thakaan.chhaya.RiderLogStore.get(ctx) }
            val air = remember { runCatching { ctx.assets.open("airquality/bengaluru_open-meteo_pm25.json").bufferedReader().use { com.thakaan.exposure.AirQualityCache.fromOpenMeteoJson(it.readText()) } }.getOrNull() }
            val nowSec = remember { System.currentTimeMillis() / 1000 }
            val dow = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }
            val pmNow = remember(air) { air?.pm25At(nowSec) }

            val latMin = g.nodeLat.min(); val latMax = g.nodeLat.max()
            val lonMin = g.nodeLon.min(); val lonMax = g.nodeLon.max()
            val kLon = cos(Math.toRadians((latMin + latMax) / 2))
            val spanX = (lonMax - lonMin) * kLon; val spanY = latMax - latMin

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = onBack) { Text("Back", fontSize = 16.sp) }
                    Text("Chhaya route", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    if (live != null) ProvenanceChip(if (override != null) com.thakaan.core.Provenance.SIMULATED else live.provenance)
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Strain:", fontSize = 13.sp)
                    if (live != null) FilterChip(selected = override == null, onClick = { override = null }, label = { Text("Live (${live.level.word})", fontSize = 13.sp) })
                    Level.entries.forEach { l -> FilterChip(selected = (override ?: if (live == null) Level.WARMING else null) == l, onClick = { override = l }, label = { Text(l.word, fontSize = 13.sp) }) }
                }
                if (override != null || live == null) Text("Demo strain: SIMULATED, not your real level.", fontSize = 12.sp, color = Color(0xFF8A5A00))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MapLayer.entries.forEach { l -> FilterChip(selected = layer == l, onClick = { layer = l }, label = { Text(l.label, fontSize = 15.sp) }) }
                }

                if (layer == MapLayer.SHADE) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Shade from:", fontSize = 13.sp)
                        ShadeSource.entries.forEach { o -> FilterChip(selected = source == o, onClick = { source = o }, label = { Text(o.label, fontSize = 13.sp) }) }
                    }
                }
                Box(Modifier.fillMaxWidth().aspectRatio(1f).background(Color(0xFFF4F6F8), RoundedCornerShape(10.dp))) {
                    Canvas(
                        Modifier.fillMaxSize().clipToBounds()
                            .pointerInput(g) {
                                detectTransformGestures { centroid, pd, z, _ ->
                                    val nz = (zoom * z).coerceIn(1f, 8f); val f = nz / zoom
                                    pan = Offset((pan.x - centroid.x) * f + centroid.x + pd.x, (pan.y - centroid.y) * f + centroid.y + pd.y)
                                    zoom = nz
                                    if (zoom == 1f) pan = Offset.Zero
                                }
                            }
                            .pointerInput(g) {
                                detectTapGestures { p ->
                                    val base = baseScale(size.width.toFloat(), size.height.toFloat(), spanX, spanY)
                                    val ox = (size.width - spanX * base) / 2f; val oy = (size.height - spanY * base) / 2f
                                    val bx = ((p.x - pan.x) / zoom - ox) / base; val by = ((p.y - pan.y) / zoom - oy) / base
                                    dest = (latMax - by) to (lonMin + bx / kLon)
                                }
                            },
                    ) {
                        val base = baseScale(size.width, size.height, spanX, spanY)
                        val ox = (size.width - spanX * base) / 2f; val oy = (size.height - spanY * base) / 2f
                        fun px(lon: Double) = ((((lon - lonMin) * kLon) * base + ox) * zoom + pan.x).toFloat()
                        fun py(lat: Double) = (((latMax - lat) * base + oy) * zoom + pan.y).toFloat()
                        fun seg(c: Color, a: IntArray, i: Int, w: Float) =
                            drawLine(c, Offset(px(a[i + 1] / 1e6), py(a[i] / 1e6)), Offset(px(a[i + 3] / 1e6), py(a[i + 2] / 1e6)), strokeWidth = w, cap = StrokeCap.Round)

                        if (layer == MapLayer.AIR && pmNow != null) drawRect(airTint(com.thakaan.chhaya.PmBand.of(pmNow)))
                        // roads: grey base, so the chosen layer is the only colour on the map
                        for (e in 0 until g.edgeCount) { val geom = g.edgeGeom[e]; for (i in 0 until geom.size - 2 step 2) seg(ROAD_GREY, geom, i, 2.5f) }
                        if (layer == MapLayer.SHADE) {
                            for (e in 0 until g.edgeCount) {
                                val sun = data.shade.sunFraction(e, minute)
                                val c = if (sun < 0.35) SHADE_GREEN else if (sun > 0.65) SUN else PART_SHADE
                                val geom = g.edgeGeom[e]
                                for (i in 0 until geom.size - 2 step 2) seg(c, geom, i, 5f)
                            }
                        }
                        if (layer == MapLayer.SIGNAL) {
                            for (e in rider.signalEdges()) {
                                val c = when (com.thakaan.chhaya.SignalBand.of(rider.signalMean(e) ?: 0.0)) { com.thakaan.chhaya.SignalBand.GOOD -> SHADE_GREEN; com.thakaan.chhaya.SignalBand.WEAK -> SIGNAL_WEAK; com.thakaan.chhaya.SignalBand.POOR -> FLOOD_RED }
                                val geom = g.edgeGeom[e]
                                for (i in 0 until geom.size - 2 step 2) seg(Color.White, geom, i, 11f)
                                for (i in 0 until geom.size - 2 step 2) seg(c, geom, i, 7f)
                            }
                        }
                        if (layer == MapLayer.TRAFFIC) {
                            val observed = rider.speedEdges()
                            for (e in 0 until g.edgeCount) {
                                val band = if (e in observed) com.thakaan.chhaya.TrafficEstimate.bandOfRatio(rider.speedRatio(e) ?: 1.0) else com.thakaan.chhaya.TrafficEstimate.band(com.thakaan.chhaya.TrafficEstimate.slowdown(g.edgeSpeedKmh[e], dow, minute))
                                val c = when (band) { com.thakaan.chhaya.TrafficEstimate.Band.FREE -> SHADE_GREEN; com.thakaan.chhaya.TrafficEstimate.Band.SLOW -> SIGNAL_WEAK; com.thakaan.chhaya.TrafficEstimate.Band.JAMMED -> FLOOD_RED }
                                val geom = g.edgeGeom[e]
                                if (e in observed) for (i in 0 until geom.size - 2 step 2) seg(Color.White, geom, i, 13f)
                                for (i in 0 until geom.size - 2 step 2) seg(c, geom, i, if (e in observed) 9f else 4f)
                            }
                        }
                        // the plain fastest route, faint and dashed, so the shadier route can be compared with it
                        if (route != null && route.arcs != route.fastestArcs) route.fastestArcs.forEach { a ->
                            val pts = g.arcPolyline(a)
                            for (i in 0 until pts.size - 1) drawLine(Color(0xFF455A64), Offset(px(pts[i].second), py(pts[i].first)), Offset(px(pts[i + 1].second), py(pts[i + 1].first)), strokeWidth = 5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 10f)))
                        }
                        // route: all white edges first, then the blue line, so joints between segments stay solid
                        route?.arcs?.forEach { a ->
                            val pts = g.arcPolyline(a)
                            for (i in 0 until pts.size - 1) drawLine(Color.White, Offset(px(pts[i].second), py(pts[i].first)), Offset(px(pts[i + 1].second), py(pts[i + 1].first)), strokeWidth = 17f, cap = StrokeCap.Round)
                        }
                        route?.arcs?.forEach { a ->
                            val pts = g.arcPolyline(a)
                            for (i in 0 until pts.size - 1) drawLine(ROUTE_BLUE, Offset(px(pts[i].second), py(pts[i].first)), Offset(px(pts[i + 1].second), py(pts[i + 1].first)), strokeWidth = 11f, cap = StrokeCap.Round)
                        }
                        // water and shelters: small dots always, big labelled markers when the Water layer is chosen
                        val big = layer == MapLayer.WATER
                        g.pois.filter { it.type == PoiType.WATER || it.type == PoiType.SHELTER }.forEach { p ->
                            val c = if (p.type == PoiType.WATER) WATER_BLUE else SHELTER_PURPLE
                            val o = Offset(px(p.lon), py(p.lat))
                            drawCircle(Color.White, if (big) 17f else 11f, o)
                            drawCircle(c, if (big) 13f else 8f, o)
                            if (big) label(measurer, if (p.type == PoiType.WATER) "Water" else "Shelter", o + Offset(16f, -12f), c)
                        }
                        // reported floods drawn last so nothing hides them
                        floods.active(System.currentTimeMillis()).forEach { fr ->
                            for (fe in fr.edges) { val gm = g.edgeGeom[fe]; for (i in 0 until gm.size - 2 step 2) seg(Color.White, gm, i, 20f) }
                            for (fe in fr.edges) { val gm = g.edgeGeom[fe]; for (i in 0 until gm.size - 2 step 2) seg(FLOOD_RED, gm, i, 12f) }
                            val geom = g.edgeGeom[fr.edge]
                            val m = geom.size / 4 * 2
                            val o = Offset(px(geom[m + 1] / 1e6), py(geom[m] / 1e6))
                            drawCircle(FLOOD_RED, 36f, o, style = Stroke(6f))
                            label(measurer, "FLOODED", o + Offset(-30f, -64f), FLOOD_RED)
                        }
                        route?.stop?.let { s ->
                            val o = Offset(px(s.lon), py(s.lat))
                            drawCircle(Color.White, 22f, o); drawCircle(ROUTE_BLUE, 22f, o, style = Stroke(5f))
                            label(measurer, "Stop here", o + Offset(26f, -14f), ROUTE_BLUE)
                        }
                        val you = Offset(px(origin.second), py(origin.first))
                        drawCircle(Color.White, 20f, you); drawCircle(Color.Black, 14f, you)
                        label(measurer, "You", you + Offset(22f, -14f), Color.Black)
                        val to = Offset(px(dest.second), py(dest.first))
                        drawCircle(Color.White, 22f, to); drawCircle(Color(0xFFC62828), 16f, to)
                        label(measurer, "Destination (tap map to move)", to + Offset(24f, -14f), Color(0xFFC62828))
                    }
                    Text(
                        when (layer) {
                            MapLayer.SHADE -> "Roads coloured by sun or shade right now"
                            MapLayer.WATER -> "Where to drink or cool down"
                            MapLayer.FLOODS -> "Roads riders reported flooded"
                            MapLayer.SIGNAL -> "Mobile signal where this phone has been"
                            MapLayer.TRAFFIC -> "Typical traffic (estimated) and your own speed"
                            MapLayer.AIR -> "Area-average air, same on every street"
                        },
                        Modifier.align(Alignment.TopStart).padding(8.dp).background(Color(0xE6FFFFFF), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    )
                }
                Legend(layer)
                LayerNote(layer, rider, pmNow, air, nowSec, dow, minute)
                if (route == null) {
                    Text("No route found between these points.", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                } else {
                    Column(Modifier.fillMaxWidth().background(Color(0xFFE8F0FE), RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text(route.chip, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = ROUTE_BLUE)
                        Text(route.note, fontSize = 16.sp)
                        Text("%.1f km, %.1f min walking".format(route.distanceM / 1000.0, route.timeSec / 60.0), fontSize = 14.sp)
                        if (route.arcs != route.fastestArcs) Text(
                            "Fastest route (dashed grey): %.1f min, %.0f%% shade".format(route.fastestTimeSec / 60.0, route.fastestShadeShare * 100),
                            fontSize = 14.sp, color = Color(0xFF455A64),
                        )
                    }
                    Text(
                        (if (source == ShadeSource.MAP) "Shade is computed from map data (OSM), not measured. %.0f%% of this route rests on guessed building heights." else "Shade is modelled from 2023 satellite building and tree heights, not measured. %.0f%% of this route is on segments where it is unsure.").format(route.lowConfidenceShare * 100),
                        fontSize = 11.sp, color = Color.DarkGray,
                    )
                }
                FloodReportRow(g, floods, pos ?: origin, ctx) { floodVersion += 1 }
                CrewMeshCard()
                Text("© OpenStreetMap contributors", fontSize = 11.sp)
            }
        }
    }
}

private fun baseScale(w: Float, h: Float, spanX: Double, spanY: Double): Float = min(w / spanX.toFloat(), h / spanY.toFloat())

/** Soft background tint for a CPCB PM2.5 band; deeper colour for worse air. The band word is on the legend and in the note, so colour is never the only cue. */
private fun airTint(b: com.thakaan.chhaya.PmBand): Color = when (b) {
    com.thakaan.chhaya.PmBand.GOOD -> Color(0x4D43A047)
    com.thakaan.chhaya.PmBand.SATISFACTORY -> Color(0x4D9CCC65)
    com.thakaan.chhaya.PmBand.MODERATE -> Color(0x4DFDD835)
    com.thakaan.chhaya.PmBand.POOR -> Color(0x4DFB8C00)
    com.thakaan.chhaya.PmBand.VERY_POOR -> Color(0x4DE53935)
    com.thakaan.chhaya.PmBand.SEVERE -> Color(0x4D8E24AA)
}

/** One plain paragraph under the legend: where the layer's numbers come from and what they cannot tell you. */
@Composable
private fun LayerNote(layer: MapLayer, rider: com.thakaan.chhaya.RiderLog, pmNow: Double?, air: com.thakaan.exposure.AirQualityCache?, nowSec: Long, dow: Int, minute: Int) {
    val text = when (layer) {
        MapLayer.SIGNAL -> {
            val n = rider.signalEdges().size
            if (n == 0) "No signal data yet. While Thakaan is sensing, this phone notes its own mobile signal on each street you pass; a street is coloured after 3 samples. Nothing leaves the phone."
            else "$n streets measured by this phone's own radio. Grey streets have not been measured. Signal differs by network and phone, so this is a guide to dead zones, not a coverage map."
        }
        MapLayer.TRAFFIC -> {
            val n = rider.speedEdges().size
            val peak = com.thakaan.chhaya.TrafficEstimate.slowdown(28.0, dow, minute) >= 0.4
            "Estimated, not live. Thin lines assume a typical pattern for each road type at this hour (${if (peak) "rush hour now" else "outside rush hour"}); no real traffic feed is used. " +
                (if (n == 0) "Ride with Thakaan sensing to add your own measured speeds (thick lines)." else "$n streets show your measured riding speed against the road's free-flow speed.")
        }
        MapLayer.AIR -> if (pmNow == null) "No air data for this time (the offline cache covers 8 to 13 October 2026)." else {
            val next = (1..4).mapNotNull { h -> air?.pm25At(nowSec + h * 3600L)?.let { "+${h}h %.0f".format(it) } }.joinToString("  ")
            "PM2.5 about %.0f micrograms per cubic metre now (%s). This is a modelled value for the whole area (Copernicus CAMS through Open-Meteo, about 11 km grid), so every street has the same colour: it cannot show which street is worse. Bands follow India's CPCB scale for 24-hour averages. Next hours: %s".format(pmNow, com.thakaan.chhaya.PmBand.of(pmNow).word, next)
        }
        else -> return
    }
    Text(text, fontSize = 13.sp, color = Color(0xFF37474F), modifier = Modifier.fillMaxWidth().background(Color(0xFFF1F3F5), RoundedCornerShape(8.dp)).padding(8.dp))
}

private fun DrawScope.label(m: TextMeasurer, text: String, at: Offset, color: Color) {
    val r = m.measure(text, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Bold, color = color))
    val pos = Offset(at.x.coerceIn(6f, (size.width - r.size.width - 8f).coerceAtLeast(6f)), at.y.coerceIn(6f, (size.height - r.size.height - 8f).coerceAtLeast(6f)))
    drawRoundRect(Color(0xF2FFFFFF), pos - Offset(5f, 3f), Size(r.size.width + 10f, r.size.height + 6f), cornerRadius = CornerRadius(8f, 8f))
    drawText(r, topLeft = pos)
}

@Composable
private fun Legend(layer: MapLayer) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        when (layer) {
            MapLayer.SHADE -> { Swatch(SHADE_GREEN, "Shade"); Swatch(PART_SHADE, "Part shade"); Swatch(SUN, "Full sun"); Swatch(ROUTE_BLUE, "Your route"); Swatch(Color(0xFF455A64), "Fastest (dashed)") }
            MapLayer.WATER -> { Dot(WATER_BLUE, "Water (listed, not verified)"); Dot(SHELTER_PURPLE, "Shelter"); Swatch(ROUTE_BLUE, "Route") }
            MapLayer.FLOODS -> { Swatch(FLOOD_RED, "Reported flooded (3 h)"); Swatch(ROUTE_BLUE, "Route avoids it") }
            MapLayer.SIGNAL -> { Swatch(SHADE_GREEN, "Good"); Swatch(SIGNAL_WEAK, "Weak"); Swatch(FLOOD_RED, "Poor or none"); Swatch(ROAD_GREY, "Not measured yet") }
            MapLayer.TRAFFIC -> { Swatch(SHADE_GREEN, "Free"); Swatch(SIGNAL_WEAK, "Slow"); Swatch(FLOOD_RED, "Jammed"); Swatch(Color.DarkGray, "Thick = measured by you, thin = typical estimate") }
            MapLayer.AIR -> com.thakaan.chhaya.PmBand.entries.forEach { Swatch(airTint(it).copy(alpha = 1f), it.word) }
        }
    }
}

@Composable
private fun Swatch(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(width = 22.dp, height = 6.dp).background(c, RoundedCornerShape(3.dp)))
        Text(text, fontSize = 12.sp)
    }
}

@Composable
private fun Dot(c: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(12.dp).background(c, RoundedCornerShape(6.dp)))
        Text(text, fontSize = 12.sp)
    }
}
