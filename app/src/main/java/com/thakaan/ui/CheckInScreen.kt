package com.thakaan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The daily 3-tap check-in (#28): slept, ate, unwell. Three questions, one tap each, nothing typed, every answer optional.
 * A skip stores all-unknown, and unknown never counts as a benign cause. Never blocks the job: Skip is always there.
 */
@Composable
fun CheckInScreen(onAnswer: (sleptWell: Boolean?, ate: Boolean?, unwell: Boolean?) -> Unit) {
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var slept by remember { mutableStateOf<Boolean?>(null) }
            var ate by remember { mutableStateOf<Boolean?>(null) }
            var unwell by remember { mutableStateOf<Boolean?>(null) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Today's check-in", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Three taps. It stays on this phone.", fontSize = 16.sp, color = Color.DarkGray)
                Question("Did you sleep well last night?", slept) { slept = it }
                Question("Have you eaten today?", ate) { ate = it }
                Question("Do you feel unwell right now?", unwell) { unwell = it }
                Button(onClick = { onAnswer(slept, ate, unwell) }, Modifier.fillMaxWidth().height(64.dp)) { Text("Done", fontSize = 22.sp) }
                OutlinedButton(onClick = { onAnswer(null, null, null) }, Modifier.fillMaxWidth().height(56.dp)) { Text("Skip", fontSize = 18.sp) }
            }
        }
    }
}

@Composable
private fun Question(text: String, value: Boolean?, set: (Boolean?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(text, fontSize = 20.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Yes" to true, "No" to false).forEach { (label, v) ->
                val on = value == v
                val mod = Modifier.weight(1f).height(56.dp)
                // Tapping the chosen answer again clears it, so "unknown" is always reachable.
                if (on) Button(onClick = { set(null) }, mod) { Text("✓ $label", fontSize = 20.sp) }
                else OutlinedButton(onClick = { set(v) }, mod) { Text(label, fontSize = 20.sp) }
            }
        }
    }
}
