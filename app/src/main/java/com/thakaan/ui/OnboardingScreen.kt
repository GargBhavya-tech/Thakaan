package com.thakaan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thakaan.risk.Lang
import com.thakaan.settings.OnboardingFlow
import com.thakaan.settings.OnboardingStep
import com.thakaan.settings.UserProfile

private val LANG_NAMES = mapOf(Lang.HI to "हिन्दी", Lang.EN to "English", Lang.KN to "ಕನ್ನಡ", Lang.TA to "தமிழ்", Lang.TE to "తెలుగు")

/**
 * First-run setup (#28): language, body size, heat experience, an emergency number and what is shared. About a minute.
 * Everything is stored on this phone only. Optional answers left blank keep the cautious defaults. DEVICE-UNTESTED.
 */
@Composable
fun OnboardingScreen(onDone: (UserProfile) -> Unit) {
    val flow = remember { OnboardingFlow() }
    var tick by remember { mutableIntStateOf(0) } // OnboardingFlow is a plain class, so bump this to redraw
    var error by remember { mutableStateOf<String?>(null) }
    fun advance() {
        val bad = flow.problem()
        if (bad != null) { error = bad; return }
        error = null
        flow.next(); tick++
        if (flow.step == OnboardingStep.DONE) flow.build()?.let(onDone)
    }
    ThakaanTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                tick.let { Text("Set up Thakaan", fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                when (flow.step) {
                    OnboardingStep.LANGUAGE -> {
                        Text("Which language should alerts speak?", fontSize = 20.sp)
                        LANG_NAMES.forEach { (l, n) ->
                            (if (flow.language == l) Button(onClick = { flow.language = l; tick++ }, Modifier.fillMaxWidth().height(56.dp)) { Text(n, fontSize = 22.sp) }
                            else OutlinedButton(onClick = { flow.language = l; tick++ }, Modifier.fillMaxWidth().height(56.dp)) { Text(n, fontSize = 22.sp) })
                        }
                    }
                    OnboardingStep.BODY -> {
                        Text("Your body size sets how fast heat builds up. It stays on this phone.", fontSize = 18.sp)
                        NumberField("Weight (kg)", flow.massKg?.toString() ?: "") { flow.massKg = it.toDoubleOrNull() }
                        NumberField("Height (m, like 1.70)", flow.heightM?.toString() ?: "") { flow.heightM = it.toDoubleOrNull() }
                        NumberField("Age", flow.age?.toString() ?: "") { flow.age = it.toIntOrNull() }
                    }
                    OnboardingStep.HEAT_EXPERIENCE -> {
                        Text("How many days have you worked in this heat? If you are new or unsure, leave 0: Thakaan then warns earlier.", fontSize = 18.sp)
                        NumberField("Days in the heat", flow.acclimatisationDays.toString()) { flow.acclimatisationDays = (it.toIntOrNull() ?: 0).coerceIn(0, 30) }
                        NumberField("Resting pulse (optional)", flow.restHr?.toInt()?.toString() ?: "") { flow.restHr = it.toDoubleOrNull() }
                    }
                    OnboardingStep.CONTACT -> {
                        Text("Who should we message if you collapse? (optional)", fontSize = 18.sp)
                        OutlinedTextField(flow.contact, { flow.contact = it.filter { c -> c.isDigit() || c == '+' }.take(15); tick++ },
                            label = { Text("Phone number") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
                    }
                    OnboardingStep.PRIVACY -> {
                        Text("Your pulse, brain scores and symptoms never leave this phone. Your supervisor sees only your level.", fontSize = 18.sp)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Switch(flow.share, { flow.share = it; tick++ })
                            Text("Show my position to my supervisor", fontSize = 18.sp)
                        }
                        Text("Off by default. This is a heat-strain wellbeing tool, not a medical device.", fontSize = 16.sp, color = Color.DarkGray)
                    }
                    OnboardingStep.DONE -> Text("All set.", fontSize = 22.sp)
                }
                error?.let { Text(it, fontSize = 18.sp, color = Color(0xFFB00020)) }
                if (flow.step != OnboardingStep.DONE) {
                    Button(onClick = { advance() }, Modifier.fillMaxWidth().height(64.dp)) { Text(if (flow.step == OnboardingStep.PRIVACY) "Finish" else "Next", fontSize = 22.sp) }
                    if (flow.step != OnboardingStep.LANGUAGE) OutlinedButton(onClick = { flow.back(); error = null; tick++ }, Modifier.fillMaxWidth()) { Text("Back", fontSize = 18.sp) }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, initial: String, onValue: (String) -> Unit) {
    var text by remember(label) { mutableStateOf(if (initial == "0" || initial == "0.0") "" else initial) }
    OutlinedTextField(
        text, { text = it.filter { c -> c.isDigit() || c == '.' }.take(6); onValue(text) },
        label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth(), singleLine = true,
    )
}
