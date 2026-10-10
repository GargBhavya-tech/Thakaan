package com.thakaan.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Tab(val label: String, val glyph: Glyph) {
    TODAY("Today", Glyph.TODAY), CHECK("Check", Glyph.CHECK), ROUTE("Route", Glyph.ROUTE), REPORT("Report", Glyph.REPORT),
}

private enum class Page { SETTINGS, QUICK, FACE, FACE_LIVE, SAFETY, DIAGNOSTICS }

/**
 * The app frame: four tabs (Today, Check, Route, Report) with a bottom bar, and everything else pushed on top as a page with a
 * back stack, so back from Diagnostics returns to Settings and back from Settings returns to the tab you were on. The bottom
 * bar hides while a page is open so a check in progress is never one stray tap from leaving.
 */
@Composable
fun AppShell(
    running: Boolean,
    recording: Boolean,
    replaying: Boolean,
    onToggleHub: () -> Unit,
    onToggleRecord: () -> Unit,
    onReplay: (Float) -> Unit,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.TODAY) }
    var stack by remember { mutableStateOf(listOf<Page>()) }
    fun push(p: Page) { stack = stack + p }
    fun pop() { stack = stack.dropLast(1) }
    BackHandler(enabled = stack.isNotEmpty()) { pop() }

    when (stack.lastOrNull()) {
        Page.SETTINGS -> SettingsScreen(
            running = running, recording = recording, replaying = replaying, onToggleRecord = onToggleRecord, onReplay = onReplay,
            onOpenSafety = { push(Page.SAFETY) }, onOpenDiagnostics = { push(Page.DIAGNOSTICS) }, onOpenFaceLive = { push(Page.FACE_LIVE) }, onBack = ::pop,
        )
        Page.QUICK -> QuickCheckScreen(onBack = ::pop)
        Page.FACE -> FaceCheckScreen(onBack = ::pop)
        Page.FACE_LIVE -> FaceLiveScreen(onBack = ::pop)
        Page.SAFETY -> SafetyScreen(onBack = ::pop)
        Page.DIAGNOSTICS -> DiagnosticsScreen(onBack = ::pop)
        null -> ThakaanTheme {
            Scaffold(
                containerColor = Ink.ground,
                bottomBar = {
                    Column {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.line))
                        NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                            Tab.entries.forEach { t ->
                                NavigationBarItem(
                                    selected = tab == t, onClick = { tab = t },
                                    icon = { GlyphIcon(t.glyph, if (tab == t) Ink.accent else Ink.sub) },
                                    label = { Text(t.label, fontSize = 13.sp, fontWeight = if (tab == t) FontWeight.Bold else FontWeight.Medium) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = Ink.accentSoft, selectedTextColor = Ink.accent, unselectedTextColor = Ink.sub,
                                        selectedIconColor = Ink.accent, unselectedIconColor = Ink.sub,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { inner ->
                Box(Modifier.fillMaxSize().padding(inner)) {
                    when (tab) {
                        Tab.TODAY -> HomeScreen(running = running, replaying = replaying, onToggleHub = onToggleHub, onOpenSettings = { push(Page.SETTINGS) }, onOpenQuickCheck = { push(Page.QUICK) })
                        Tab.CHECK -> CheckScreen(onQuick = { push(Page.QUICK) }, onFace = { push(Page.FACE) })
                        Tab.ROUTE -> ChhayaScreen(onBack = null)
                        Tab.REPORT -> ShiftReportScreen(onBack = null)
                    }
                }
            }
        }
    }
}

/** The Check tab: the checks a worker can run, the quick one first. */
@Composable
private fun CheckScreen(onQuick: () -> Unit, onFace: () -> Unit) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PageHeader("Check", null)
                AppCard {
                    Text("Quick check", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Fingertip pulse, hand steadiness, tapping and how you feel. About 95 seconds. A step you skip is marked skipped, never guessed.", fontSize = 15.sp, color = Ink.sub)
                    PrimaryAction("Start quick check", onClick = onQuick)
                }
                AppCard {
                    Text("Face check (optional)", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text("About 40 seconds with the front camera: tiredness signals and a pulse estimate. The camera pictures stay in memory and are never saved.", fontSize = 15.sp, color = Ink.sub)
                    SecondaryAction("Start face check", onClick = onFace)
                }
                Text("These checks support the level; they never set it alone. Heat-strain wellbeing tool, not a medical device.", fontSize = 12.sp, color = Ink.sub, modifier = Modifier.padding(horizontal = 4.dp))
            }
        }
    }
}
