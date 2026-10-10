package com.thakaan.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thakaan.mesh.BleMesh
import com.thakaan.mesh.CrewMesh
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Crew mesh switch (#59): Bluetooth LE between nearby Thakaan phones, no internet. Off until the worker turns it on. Says
 * plainly what it shares: flood reports (anonymous) and, only to buddies who typed the same code, that you reached Strained or
 * Danger. Never heart rate or scores. DEVICE-UNTESTED with two phones.
 */
@Composable
fun CrewMeshCard() {
    val ctx = LocalContext.current
    val st by CrewMesh.state.collectAsStateWithLifecycle()
    var code by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { CrewMesh.init(ctx) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        msg = if (granted.values.all { it }) CrewMesh.enable(ctx) ?: "" else "Bluetooth permission is needed for the crew mesh"
    }
    Column(Modifier.fillMaxWidth().border(1.dp, Color.Gray, RoundedCornerShape(10.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Crew mesh (Bluetooth, no internet)", fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Switch(checked = st.enabled, onCheckedChange = { on ->
                if (on) { if (BleMesh.hasPermissions(ctx)) msg = CrewMesh.enable(ctx) ?: "" else ask.launch(BleMesh.permissions()) } else CrewMesh.disable()
            })
        }
        Text(if (msg.isNotEmpty()) msg else st.status, fontSize = 14.sp, color = if (msg.isNotEmpty()) Color(0xFF9B0000) else Color.DarkGray)
        if (st.enabled) {
            val ago = st.lastHeardSec?.let { "last heard ${((System.currentTimeMillis() / 1000 - it) / 60).coerceAtLeast(0)} min ago" } ?: "nothing heard yet"
            Text("Messages heard: ${st.heard}, $ago", fontSize = 14.sp)
        }
        if (!st.paired) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = code, onValueChange = { code = it.take(12) }, label = { Text("Buddy code (same on both phones)") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { msg = if (CrewMesh.pair(ctx, code)) "" else "Use 4 to 12 characters"; if (msg.isEmpty()) code = "" }) { Text("Pair") }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Buddy pairing is on. Your buddies are told when you reach Strained or Danger.", fontSize = 13.sp, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { CrewMesh.unpair(ctx) }) { Text("Unpair") }
            }
        }
        val fmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
        st.buddy.forEach { Text("Buddy ${it.tag}: ${it.level.shape} ${it.level.word} at ${fmt.format(Date(it.atSec * 1000))}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = it.level.color) }
        Text("Shares: flood reports (no name or device) and, to paired buddies only, your level when it reaches Strained or Danger. Never heart rate or scores.", fontSize = 11.sp, color = Color.DarkGray)
    }
}
