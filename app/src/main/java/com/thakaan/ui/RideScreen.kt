package com.thakaan.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.VibratorManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.chhaya.ChhayaRouter
import com.thakaan.chhaya.FloodStore
import com.thakaan.chhaya.Maneuver
import com.thakaan.chhaya.NavRoute
import com.thakaan.chhaya.RideSim
import com.thakaan.chhaya.Route
import com.thakaan.chhaya.RoadGraph
import com.thakaan.chhaya.RoadGraphStore
import com.thakaan.chhaya.ShadeLayer
import com.thakaan.chhaya.TrafficEstimate
import com.thakaan.ride.Alertness
import com.thakaan.ride.CameraDecision
import com.thakaan.ride.Fitness
import com.thakaan.ride.RideCameraPolicy
import com.thakaan.ride.RideFitness
import com.thakaan.ride.RideMonitor
import com.thakaan.ride.Verdict
import com.thakaan.risk.Level
import com.thakaan.shift.QuickCheckOutcome
import com.thakaan.shift.ShiftRuntime
import com.thakaan.shift.ShiftSnapshot
import kotlinx.coroutines.delay
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private val DEMO_ORIGIN = 12.9655 to 77.5900

/** Where the delivery flow is. Held above the tabs so switching tabs or visiting Settings does not lose it. */
class RideSession {
    enum class Stage { WAITING, ASSESSING, VERDICT, RESTING, DELIVERED }

    var stage by mutableStateOf(Stage.WAITING)
    var dest by mutableStateOf(DEMO_DROP)
    var simulate by mutableStateOf(false)
    var pending by mutableStateOf<QuickCheckOutcome?>(null)
    var fitness by mutableStateOf<Fitness?>(null)
    var restEndsAtMs by mutableLongStateOf(0L)
    var summary by mutableStateOf<String?>(null)

    fun checkDone(o: QuickCheckOutcome) { pending = o; stage = Stage.ASSESSING }
    fun startRest(minutes: Int) { restEndsAtMs = System.currentTimeMillis() + minutes * 60_000L; stage = Stage.RESTING }
    fun finishRide(text: String) { summary = text; stage = Stage.DELIVERED }
    fun reset() { stage = Stage.WAITING; fitness = null; pending = null }

    companion object { val DEMO_DROP = 12.9850 to 77.6100 }
}

/** The road graph, its router and the route plan for a ride. The router is the same one the Route tab uses, asked for the fastest way. */
internal object RideNav {
    class Ctx(val graph: RoadGraph, val router: ChhayaRouter)
    class Plan(val nav: NavRoute, val route: Route, val etaMin: Double)

    @Volatile private var cached: Ctx? = null

    fun get(ctx: Context): Ctx? = cached ?: try {
        val g = RoadGraphStore.get(ctx) ?: return null
        val shade = ctx.assets.open("chhaya/shade.json").bufferedReader().use { ShadeLayer(it.readText()) }
        Ctx(g, ChhayaRouter(g, shade, FloodStore.get(ctx))).also { cached = it }
    } catch (_: Exception) { null }

    /** The fastest route to the drop point, with an arrival time that allows for the typical traffic of this hour on each road. */
    fun plan(c: Ctx, from: Pair<Double, Double>, to: Pair<Double, Double>, minute: Int, dow: Int): Plan? {
        val r = c.router.route(from.first, from.second, to.first, to.second, Level.COOL, 0, minute, sunUp = false) ?: return null
        val pts = ArrayList<Pair<Double, Double>>()
        r.arcs.forEach { pts += c.graph.arcPolyline(it) }
        if (pts.size < 2) return null
        var sec = 0.0
        for (a in r.arcs) {
            val e = c.graph.arcEdge[a]; val v = c.graph.edgeSpeedKmh[e] / 3.6
            sec += c.graph.edgeLenM[e] / (v * (1.0 - TrafficEstimate.slowdown(c.graph.edgeSpeedKmh[e], dow, minute).coerceAtMost(0.8)))
        }
        return Plan(NavRoute(pts), r, sec / 60.0)
    }
}

// ------------------------------------------------------------------------------------------------ the Ride tab

/**
 * The delivery partner's panel. At the restaurant: set the drop point, do the pickup check (face, pulse, how you feel), and get
 * one answer: fit to ride, rest five minutes first, or look again. A rider who is fit starts the ride screen; one who is not is
 * told to rest and drink water and can check again when ready. Nothing here blocks the job: a rider can always choose to ride.
 */
@Composable
fun RideTab(
    ride: RideSession, running: Boolean, onToggleHub: () -> Unit,
    onStartCheck: () -> Unit, onStartRide: () -> Unit, onOpenSettings: () -> Unit,
) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ctx = LocalContext.current
            val nav = remember { RideNav.get(ctx) }
            val minute = remember { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
            val dow = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }
            val origin = ShiftRuntime.engine?.position ?: DEMO_ORIGIN
            val plan = remember(ride.dest, nav) { nav?.let { RideNav.plan(it, origin, ride.dest, minute, dow) } }
            val snapNow by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            val bounds = remember(nav) { nav?.graph?.let { g -> doubleArrayOf(g.nodeLat.min(), g.nodeLat.max(), g.nodeLon.min(), g.nodeLon.max()) } }

            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ride", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Ink.ink, modifier = Modifier.weight(1f).padding(start = 4.dp))
                    IconAction(Glyph.GEAR, "Settings", onOpenSettings)
                }
                snapNow?.let { CollapseBanner(it) }
                when (ride.stage) {
                    RideSession.Stage.WAITING -> {
                        if (!running) AppCard(tint = Ink.warnBg, border = Ink.warnInk) {
                            Text("Sensing is off", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.warnInk)
                            Text("Start sensing so Thakaan can check how you are doing.", fontSize = 15.sp, color = Ink.ink)
                            PrimaryAction("Start sensing", onClick = onToggleHub)
                        }
                        AppCard {
                            Text("Drop point", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Tap the map to set where you are delivering.", fontSize = 15.sp, color = Ink.sub)
                            if (nav != null) {
                                RideMap(
                                    nav.graph, plan?.nav, origin, 0.0, ride.dest, follow = false, onTap = { p -> if (bounds != null && p.first in bounds[0]..bounds[1] && p.second in bounds[2]..bounds[3]) ride.dest = p },
                                    modifier = Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(12.dp)),
                                )
                                if (plan != null) Text("%.1f km, about %.0f min by road".format(plan.route.distanceM / 1000.0, plan.etaMin), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.ink)
                                else Text("No road route to this point. Tap somewhere else.", fontSize = 15.sp, color = Ink.alertInk)
                            } else Text("Map data is missing from this build.", fontSize = 15.sp, color = Ink.alertInk)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Switch(checked = ride.simulate, onCheckedChange = { ride.simulate = it })
                                Column(Modifier.weight(1f)) {
                                    Text("Simulated ride", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Moves along the route without GPS, for demos indoors. Marked SIMULATED.", fontSize = 12.sp, color = Ink.sub)
                                }
                            }
                        }
                        AppCard {
                            Text("Before you ride", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                            Text("Check your face, pulse, a quick tapping game and how you feel. About 2 minutes. If you are fit to ride, the map starts. If not, you rest first.", fontSize = 15.sp, color = Ink.sub)
                            PrimaryAction("Start pickup check", onClick = onStartCheck, enabled = running)
                        }
                    }
                    RideSession.Stage.ASSESSING -> {
                        val o = ride.pending
                        LaunchedEffect(o) {
                            delay(1500) // let the engine take the new pulse and answers into the level
                            val level = ShiftRuntime.snapshot.value?.level ?: Level.WARMING
                            ride.fitness = RideFitness.assess(level, o?.fatigue, o?.symptoms)
                            ride.stage = RideSession.Stage.VERDICT
                        }
                        AppCard { Text("Reading your check...", fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    }
                    RideSession.Stage.VERDICT -> {
                        val f = ride.fitness ?: Fitness(Verdict.RETRY, "Look at the screen again", "")
                        val color = when (f.verdict) { Verdict.FIT -> Level.COOL.color; Verdict.REST -> Level.STRAINED.color; Verdict.RETRY -> Ink.sub }
                        AppCard(tint = color.copy(alpha = 0.08f).compositeOver(Color.White), border = color) {
                            Text(f.headline, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                            Text(f.detail, fontSize = 17.sp, color = Ink.ink)
                        }
                        when (f.verdict) {
                            Verdict.FIT -> {
                                PrimaryAction("Start ride", onClick = onStartRide)
                                SecondaryAction("Check again", onClick = onStartCheck)
                            }
                            Verdict.REST -> {
                                PrimaryAction("Start ${f.restMinutes} minute rest", onClick = { ride.startRest(f.restMinutes) })
                                if (f.restMinutes < RideFitness.REST_DANGER_MIN) SecondaryAction("Ride anyway", onClick = onStartRide)
                            }
                            Verdict.RETRY -> PrimaryAction("Check again", onClick = onStartCheck)
                        }
                    }
                    RideSession.Stage.RESTING -> {
                        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                        LaunchedEffect(Unit) { while (true) { delay(1000); now = System.currentTimeMillis() } }
                        val left = ((ride.restEndsAtMs - now) / 1000).coerceAtLeast(0)
                        AppCard(tint = Ink.warnBg, border = Ink.warnInk) {
                            Text(if (left > 0) "Rest and drink water" else "Rest is done", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink.warnInk)
                            Text("%d:%02d".format(left / 60, left % 60), fontSize = 56.sp, fontWeight = FontWeight.Bold, color = Ink.ink, style = Tabular)
                            Text("Sit in the shade. Drink some water. Then check again.", fontSize = 16.sp, color = Ink.ink)
                        }
                        PrimaryAction("Check again", onClick = onStartCheck)
                    }
                    RideSession.Stage.DELIVERED -> {
                        AppCard(tint = Level.COOL.color.copy(alpha = 0.08f).compositeOver(Color.White), border = Level.COOL.color) {
                            Text("Delivered", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Level.COOL.color)
                            Text(ride.summary ?: "", fontSize = 16.sp, color = Ink.ink)
                        }
                        PrimaryAction("Next pickup", onClick = { ride.reset() })
                    }
                }
                Text("Heat-strain wellbeing tool, not a medical device.", fontSize = 12.sp, color = Ink.sub, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ the riding page

/**
 * The screen on the handlebar: the next turn at the top, the map and the score side by side, and two buttons. The camera follows
 * [RideCameraPolicy] (on in short windows while riding steadily, off when stopped, near the drop point or when the phone is hot),
 * and each window updates the eyes line and the strain score. In a simulated ride the phone moves along the route by itself.
 * Directions come from the offline road graph (it has no street names), so they say what to do and how far.
 */
@Composable
fun RidingPage(ride: RideSession, onFinished: () -> Unit, onRest: (Int) -> Unit) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val ctx = LocalContext.current
            val cn = remember { RideNav.get(ctx) }
            if (cn == null) {
                Column(Modifier.padding(16.dp)) { Text("Map data is missing from this build.", fontSize = 20.sp); PrimaryAction("Back", onClick = onFinished) }
                return@Surface
            }
            val engine = ShiftRuntime.engine
            val minute = remember { Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) } }
            val dow = remember { Calendar.getInstance().get(Calendar.DAY_OF_WEEK) }
            val snap by ShiftRuntime.snapshot.collectAsStateWithLifecycle()
            var plan by remember { mutableStateOf<RideNav.Plan?>(null) }
            var rider by remember { mutableStateOf(engine?.position ?: DEMO_ORIGIN) }
            var speed by remember { mutableStateOf<Double?>(null) }
            var heading by remember { mutableStateOf(0.0) }
            var progress by remember { mutableStateOf(0.0) }
            var gpsFresh by remember { mutableStateOf(ride.simulate) }
            var decision by remember { mutableStateOf(CameraDecision(false, "Camera off: waiting for steady riding")) }
            var alertness by remember { mutableStateOf(Alertness.WAITING) }
            var watching by remember { mutableStateOf(false) }
            var note by remember { mutableStateOf<String?>(null) }
            var drowsyBanner by remember { mutableStateOf(false) }
            var offRoute by remember { mutableIntStateOf(0) }
            val policy = remember { RideCameraPolicy() }
            val monitor = remember { RideMonitor(ctx) }
            DisposableEffect(Unit) { onDispose { monitor.stop() } }

            LaunchedEffect(Unit) {
                plan = RideNav.plan(cn, rider, ride.dest, minute, dow)
                val sim = if (ride.simulate) plan?.let { RideSim(it.nav) } else null
                var t = 0.0
                var last = rider
                while (true) {
                    delay(1000); t += 1.0
                    val p = plan ?: continue
                    var arrived = false
                    if (sim != null) {
                        val st = sim.step(1.0)
                        rider = st.lat to st.lon; speed = st.speedMs; arrived = st.arrived; gpsFresh = true
                    } else if (engine != null) {
                        val nowNs = ShiftRuntime.snapshot.value?.tNanos ?: 0L
                        gpsFresh = engine.gnssTNanos != 0L && nowNs - engine.gnssTNanos < 10_000_000_000L
                        if (gpsFresh) { rider = engine.position; speed = engine.gnssSpeedMs } else speed = null
                    }
                    if (NavRoute.metres(last, rider) > 3.0) { heading = NavRoute.bearing(last, rider); last = rider }
                    val pr = p.nav.project(rider.first, rider.second)
                    progress = pr.progressM
                    if (sim == null && gpsFresh && pr.lateralM > 40.0) {
                        offRoute++
                        if (offRoute >= 6) { RideNav.plan(cn, rider, ride.dest, minute, dow)?.let { plan = it }; offRoute = 0 }
                    } else offRoute = 0
                    val distTo = NavRoute.metres(rider, ride.dest)

                    decision = policy.decide(t, speed, distTo, RideMonitor.phoneHot(ctx), monitor.drowsyRecently)
                    monitor.follow(decision.on)
                    watching = monitor.watching
                    if (monitor.poll()) {
                        alertness = monitor.alertness
                        if (alertness == Alertness.DROWSY) { drowsyBanner = true; buzz(ctx) }
                    }
                    if (monitor.alertness == Alertness.NO_FACE) alertness = Alertness.NO_FACE
                    note = monitor.note

                    if (arrived || progress >= p.nav.totalM - 25.0 || distTo < 25.0) {
                        monitor.stop()
                        ride.finishRide("%.1f km ridden. Camera windows: %d, with drowsy signs: %d.".format(p.nav.totalM / 1000.0, monitor.windows, monitor.drowsyWindows))
                        onFinished()
                        break
                    }
                }
            }

            Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                NavBanner(plan, progress, ride.simulate, gpsFresh)
                snap?.let { sn ->
                    CollapseBanner(sn)
                    if (sn.level >= Level.STRAINED && sn.collapseSecondsLeft == 0 && !sn.collapseAlarm) AppCard(tint = Ink.alertBg, border = Ink.alertInk) {
                        Text(sn.outlook?.advice ?: "Stop, rest in shade and drink water now.", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink.alertInk)
                    }
                }
                if (drowsyBanner) AppCard(tint = Ink.alertBg, border = Ink.alertInk) {
                    Text("Drowsy signs. Pull over safely and rest.", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink.alertInk)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrimaryAction("Rest 5 min", onClick = { monitor.stop(); onRest(RideFitness.REST_MIN) }, modifier = Modifier.weight(1f), danger = true)
                        SecondaryAction("Dismiss", onClick = { drowsyBanner = false }, modifier = Modifier.weight(1f))
                    }
                }
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RideMap(
                        cn.graph, plan?.nav, rider, heading, ride.dest, follow = true, onTap = null,
                        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(16.dp)),
                    )
                    ScoreColumn(snap, alertness, decision, watching, note, speed?.let { (it * 3.6).toInt() }, Modifier.width(124.dp).fillMaxHeight())
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryAction("Rest 5 min", onClick = { monitor.stop(); onRest(RideFitness.REST_MIN) }, modifier = Modifier.weight(1f))
                    PrimaryAction("Delivered", onClick = {
                        monitor.stop()
                        ride.finishRide("Marked delivered. Camera windows: %d, with drowsy signs: %d.".format(monitor.windows, monitor.drowsyWindows))
                        onFinished()
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun buzz(ctx: Context) {
    val v = (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400, 200, 400), -1))
}

@Composable
private fun NavBanner(plan: RideNav.Plan?, progress: Double, simulated: Boolean, gpsFresh: Boolean) {
    Row(
        Modifier.fillMaxWidth().background(Ink.accent, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val step = plan?.nav?.nextStep(progress)
        if (step == null || plan == null) {
            Text(if (plan == null) "Finding the route..." else "You have arrived", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Color.White, modifier = Modifier.weight(1f))
        } else {
            ManeuverArrow(step.maneuver)
            Column(Modifier.weight(1f)) {
                val d = NavRoute.distanceText(step.atM - progress)
                Text(if (d == "now") "Now" else d, fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Color.White, style = Tabular)
                Text(step.maneuver.word, fontSize = 18.sp, color = Color.White)
            }
            val remaining = (plan.nav.totalM - progress).coerceAtLeast(0.0)
            Column(horizontalAlignment = Alignment.End) {
                Text("%.1f km".format(remaining / 1000.0), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White, style = Tabular)
                Text("about %.0f min".format(max(1.0, plan.etaMin * remaining / max(1.0, plan.nav.totalM))), fontSize = 14.sp, color = Color(0xCCFFFFFF), style = Tabular)
                if (simulated) Text("SIMULATED", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F))
                else if (!gpsFresh) Text("Waiting for GPS", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFFD54F))
            }
        }
    }
}

@Composable
private fun ManeuverArrow(m: Maneuver) {
    Canvas(Modifier.size(56.dp)) {
        val w = size.width; val h = size.height; val cx = w / 2
        if (m == Maneuver.ARRIVE) {
            drawCircle(Color.White, w * 0.30f, Offset(cx, h * 0.42f), style = Stroke(w * 0.11f))
            drawCircle(Color.White, w * 0.10f, Offset(cx, h * 0.42f))
            drawLine(Color.White, Offset(cx, h * 0.72f), Offset(cx, h * 0.92f), w * 0.11f, StrokeCap.Round)
            return@Canvas
        }
        val deg = when (m) {
            Maneuver.SLIGHT_RIGHT -> 45f; Maneuver.RIGHT -> 90f; Maneuver.SHARP_RIGHT -> 135f; Maneuver.UTURN -> 180f
            Maneuver.SLIGHT_LEFT -> -45f; Maneuver.LEFT -> -90f; Maneuver.SHARP_LEFT -> -135f
            else -> 0f
        }
        rotate(deg, Offset(cx, h / 2)) {
            drawLine(Color.White, Offset(cx, h * 0.92f), Offset(cx, h * 0.30f), w * 0.13f, StrokeCap.Round)
            val head = Path().apply { moveTo(cx, h * 0.04f); lineTo(cx - w * 0.30f, h * 0.40f); lineTo(cx + w * 0.30f, h * 0.40f); close() }
            drawPath(head, Color.White)
        }
    }
}

@Composable
private fun ScoreColumn(s: ShiftSnapshot?, alertness: Alertness, cam: CameraDecision, watching: Boolean, note: String?, speedKmh: Int?, modifier: Modifier) {
    Column(
        modifier.background(Ink.card, RoundedCornerShape(16.dp)).border(BorderStroke(1.dp, Ink.line), RoundedCornerShape(16.dp)).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Text("SCORE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.sub, letterSpacing = 1.sp)
        if (s != null) {
            Text("${s.level.shape} ${s.level.word}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = s.level.color)
            Text("Strain ${s.tss}", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = Ink.ink, style = Tabular)
        } else Text("Start sensing", fontSize = 15.sp, color = Ink.sub)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Text("EYES", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.sub, letterSpacing = 1.sp)
        val c = when (alertness) { Alertness.ALERT -> Level.COOL.color; Alertness.DROWSY -> Level.STRAINED.color; else -> Ink.sub }
        Text(alertness.word, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = c)
        if (alertness == Alertness.NO_FACE && note != null) Text(note, fontSize = 11.sp, color = Ink.sub)
        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
        Text("CAMERA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Ink.sub, letterSpacing = 1.sp)
        Text(if (watching) "On" else "Off", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = if (watching) Ink.accent else Ink.sub)
        Text(cam.reason.substringAfter(": "), fontSize = 11.sp, color = Ink.sub)
        Box(Modifier.weight(1f))
        Text(if (speedKmh != null) "$speedKmh km/h" else "-- km/h", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink.ink, style = Tabular)
    }
}

// ------------------------------------------------------------------------------------------------ the map

/**
 * Roads in grey, the route in blue, the rider as an arrow and the drop point as a red pin. [follow] centres on the rider with a
 * 320 m radius (the riding screen); otherwise the whole map window is shown and a tap sets the drop point.
 */
@Composable
private fun RideMap(
    g: RoadGraph, nav: NavRoute?, rider: Pair<Double, Double>, heading: Double, dest: Pair<Double, Double>,
    follow: Boolean, onTap: ((Pair<Double, Double>) -> Unit)?, modifier: Modifier,
) {
    val latMin = remember(g) { g.nodeLat.min() }; val latMax = remember(g) { g.nodeLat.max() }
    val lonMin = remember(g) { g.nodeLon.min() }; val lonMax = remember(g) { g.nodeLon.max() }
    val tap = rememberUpdatedState(onTap)

    class Frame(val cLat: Double, val cLon: Double, val k: Double, val pxPerM: Double, val w: Float, val h: Float) {
        fun x(lon: Double) = (w / 2 + (lon - cLon) * k * NavRoute.M_PER_DEG * pxPerM).toFloat()
        fun y(lat: Double) = (h / 2 - (lat - cLat) * NavRoute.M_PER_DEG * pxPerM).toFloat()
    }
    fun frame(w: Float, h: Float): Frame {
        if (follow) {
            val k = cos(Math.toRadians(rider.first))
            return Frame(rider.first, rider.second, k, min(w, h) / (2.0 * 320.0), w, h)
        }
        val cLat = (latMin + latMax) / 2; val cLon = (lonMin + lonMax) / 2
        val k = cos(Math.toRadians(cLat))
        val spanX = (lonMax - lonMin) * k * NavRoute.M_PER_DEG; val spanY = (latMax - latMin) * NavRoute.M_PER_DEG
        return Frame(cLat, cLon, k, min(w / spanX, h / spanY) * 0.97, w, h)
    }

    Canvas(
        modifier.background(Color(0xFFF4F6F8)).pointerInput(follow) {
            detectTapGestures { p ->
                val f = frame(size.width.toFloat(), size.height.toFloat())
                val lon = f.cLon + ((p.x - f.w / 2) / f.pxPerM) / (f.k * NavRoute.M_PER_DEG)
                val lat = f.cLat - ((p.y - f.h / 2) / f.pxPerM) / NavRoute.M_PER_DEG
                tap.value?.invoke(lat to lon)
            }
        },
    ) {
        val f = frame(size.width, size.height)
        val thin = if (follow) 4f else 2.5f
        val reach = if (follow) 320.0 * 1.6 else Double.MAX_VALUE
        for (e in 0 until g.edgeCount) {
            if (follow) {
                val u = g.edgeU[e]
                val dx = (g.nodeLon[u] - f.cLon) * f.k * NavRoute.M_PER_DEG; val dy = (g.nodeLat[u] - f.cLat) * NavRoute.M_PER_DEG
                if (kotlin.math.abs(dx) > reach + 120 || kotlin.math.abs(dy) > reach + 120) continue
            }
            val gm = g.edgeGeom[e]
            for (i in 0 until gm.size - 2 step 2) drawLine(Color(0xFFB8BEC6), Offset(f.x(gm[i + 1] / 1e6), f.y(gm[i] / 1e6)), Offset(f.x(gm[i + 3] / 1e6), f.y(gm[i + 2] / 1e6)), thin, StrokeCap.Round)
        }
        nav?.let { r ->
            for (i in 0 until r.points.size - 1) drawLine(Color.White, Offset(f.x(r.points[i].second), f.y(r.points[i].first)), Offset(f.x(r.points[i + 1].second), f.y(r.points[i + 1].first)), if (follow) 20f else 14f, StrokeCap.Round)
            for (i in 0 until r.points.size - 1) drawLine(Color(0xFF0D47A1), Offset(f.x(r.points[i].second), f.y(r.points[i].first)), Offset(f.x(r.points[i + 1].second), f.y(r.points[i + 1].first)), if (follow) 12f else 8f, StrokeCap.Round)
        }
        val d = Offset(f.x(dest.second), f.y(dest.first))
        drawCircle(Color.White, 22f, d); drawCircle(Color(0xFFC62828), 16f, d)
        val me = Offset(f.x(rider.second), f.y(rider.first))
        if (follow) {
            rotate(heading.toFloat(), me) {
                val arrow = Path().apply { moveTo(me.x, me.y - 30f); lineTo(me.x - 20f, me.y + 20f); lineTo(me.x, me.y + 9f); lineTo(me.x + 20f, me.y + 20f); close() }
                drawPath(arrow, Color.White, style = Stroke(10f)); drawPath(arrow, Color(0xFF0D47A1))
            }
        } else { drawCircle(Color.White, 20f, me); drawCircle(Color.Black, 14f, me) }
    }
}
