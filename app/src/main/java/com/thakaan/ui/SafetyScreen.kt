package com.thakaan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thakaan.risk.Guards
import com.thakaan.risk.SafetyChecks

/**
 * Safety checks tab (#42): five scenarios that run the real risk engine, with a switch per guard. Turning a guard OFF must
 * turn the scenario it protects red, which is what makes "5/5 PASS" mean something. DEVICE-UNTESTED (logic is unit-tested).
 */
@Composable
fun SafetyScreen(onBack: () -> Unit) {
    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Color.White) {
            var g by remember { mutableStateOf(Guards()) }
            val results = remember(g) { SafetyChecks.runAll(g) }
            val passed = results.count { it.passed }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onBack) { Text("Back", fontSize = 18.sp) }
                Text("Safety checks: $passed/${results.size} PASS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = if (passed == results.size) Color(0xFF1B7F3B) else Color(0xFF9B0000))
                results.forEach { r ->
                    Column(
                        Modifier.fillMaxWidth().border(2.dp, if (r.passed) Color(0xFF1B7F3B) else Color(0xFF9B0000), RoundedCornerShape(8.dp)).padding(12.dp),
                    ) {
                        Text("${if (r.passed) "PASS" else "FAIL"}  ${r.id}. ${r.name}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (r.passed) Color(0xFF1B7F3B) else Color(0xFF9B0000))
                        Text(r.detail, fontSize = 14.sp, color = Color.DarkGray)
                    }
                }
                Text("Guard switches (a check that cannot fail proves nothing)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                GuardRow("Hold the last body evidence when it goes stale", g.holdWhenEvidenceStale) { g = g.copy(holdWhenEvidenceStale = it) }
                GuardRow("Exposure floor when heart rate is stale", g.exposureFloorOnStale) { g = g.copy(exposureFloorOnStale = it) }
                GuardRow("Unknown shade counts as sun", g.unknownShadeIsSun) { g = g.copy(unknownShadeIsSun = it) }
                GuardRow("Missing weather counts as hot", g.missingWbgtIsHot) { g = g.copy(missingWbgtIsHot = it) }
                GuardRow("Two brain families force at least Strained", g.brainFamiliesFloorStrained) { g = g.copy(brainFamiliesFloorStrained = it) }
                GuardRow("LLM cannot change a level", g.llmCannotChangeLevel) { g = g.copy(llmCannotChangeLevel = it) }
                Button(onClick = { g = Guards() }) { Text("All guards ON", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
private fun GuardRow(label: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().background(if (on) Color.Transparent else Color(0xFFFFEBEE)), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(checked = on, onCheckedChange = set)
    }
}
