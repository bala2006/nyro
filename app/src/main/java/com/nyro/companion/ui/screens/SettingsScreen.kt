package com.nyro.companion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.data.NyroSettings
import com.nyro.companion.ui.Routes
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import com.nyro.companion.voice.VoiceEngine
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun SettingsScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val g = NyroApp.graph
    val s by g.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    fun upd(f: (NyroSettings) -> NyroSettings) = scope.launch { g.settings.update(f) }
    var clear by remember { mutableStateOf<String?>(null) }
    clear?.let { what ->
        ConfirmClear(what, {
            scope.launch {
                when (what) {
                    "conversations" -> { g.agent.newConversation(); g.dao.clearMessages(); g.dao.clearConversations() }
                    "task history" -> g.dao.clearTasks()
                    "memories" -> g.dao.clearMemories()
                }
            }
        }) { clear = null }
    }

    ScreenFrame("Settings", onBack) {
        NavRow("AI Provider", s.provider.deployment.ifBlank { "Not configured" }) { go(Routes.PROVIDER) }
        NavRow("Permissions", "Overlay, phone control, microphone, notifications") { go(Routes.PERMISSIONS) }

        Gap(12)
        SectionLabel("Voice")
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Column {
                Toggle("Speak replies", "Read NYRO's answers aloud with Android text-to-speech.", s.speakReplies) { v -> upd { it.copy(speakReplies = v) } }
                Toggle("Continuous conversation", "After NYRO answers a spoken request, listen again automatically.", s.continuousConversation) { v -> upd { it.copy(continuousConversation = v) } }
                Text("Speech rate ${"%.1f".format(s.speechRate)}×", color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                Slider(s.speechRate, { v -> upd { it.copy(speechRate = (v * 10).toInt() / 10f) } }, valueRange = 0.5f..2f, colors = sliderColors())
                Text("Pitch ${"%.1f".format(s.speechPitch)}", color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                Slider(s.speechPitch, { v -> upd { it.copy(speechPitch = (v * 10).toInt() / 10f) } }, valueRange = 0.5f..1.6f, colors = sliderColors())
                VoicePicker(s.voiceName) { name -> upd { it.copy(voiceName = name) } }
                TextButton(onClick = { g.voice.speak("Hi, I'm NYRO. Your phone, your intelligence.") }) { Text("Test voice") }
            }
        }
        Gap(10)
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Column {
                val kind = g.voice.recognizerKind()
                Text("Speech recognition", color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                Text(
                    when (kind) {
                        VoiceEngine.RecognizerKind.ON_DEVICE -> "On-device recognition is available. Your voice is processed on this phone; only the recognized text is sent to Azure."
                        VoiceEngine.RecognizerKind.NETWORK_FALLBACK -> "Using the phone's default speech service (fallback enabled). It may send audio to that service provider (usually Google) for recognition."
                        VoiceEngine.RecognizerKind.UNAVAILABLE -> "On-device recognition isn't available on this phone. You can type, or enable the fallback below."
                    },
                    color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
                )
                Toggle(
                    "Allow speech-service fallback",
                    "If on-device recognition is unavailable, use the phone's default recognizer, which may process audio online (e.g. Google). NYRO itself never uploads audio.",
                    s.allowNetworkRecognizer,
                ) { v -> upd { it.copy(allowNetworkRecognizer = v) } }
                LanguagePicker(s.recognitionLanguage) { tag -> upd { it.copy(recognitionLanguage = tag) } }
            }
        }

        Gap(12)
        SectionLabel("Safety & privacy")
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Column {
                Toggle("Confirm medium-risk actions", "Ask before sending, sharing, posting or changing settings. High-risk actions (payments, purchases, deletion, security) always ask.", s.confirmMediumRisk) { v -> upd { it.copy(confirmMediumRisk = v) } }
                Toggle("Allow screenshots for vision", "Lets NYRO send a screenshot to your Azure endpoint when accessibility info isn't enough. A notice appears every time.", s.allowScreenshots) { v -> upd { it.copy(allowScreenshots = v) } }
            }
        }

        Gap(12)
        SectionLabel("Local data")
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Column {
                Text("Conversations, memories, tasks and settings live only in NYRO's private storage on this phone and are excluded from cloud backup.", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { clear = "conversations" }) { Text("Clear conversations", color = Nyro.Danger) }
                    TextButton(onClick = { clear = "task history" }) { Text("Clear tasks", color = Nyro.Danger) }
                }
                TextButton(onClick = { clear = "memories" }) { Text("Clear memories", color = Nyro.Danger) }
            }
        }

        Gap(12)
        SectionLabel("What Android doesn't allow (without root)")
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Text(
                "• Toggling Wi-Fi, Bluetooth, mobile data or airplane mode directly. NYRO opens the setting or panel and taps the switch for you.\n" +
                    "• Reading or capturing secure screens (banking apps, DRM video, password fields).\n" +
                    "• Bypassing lock screen, biometrics, permission dialogs or entering passwords/OTPs. NYRO always hands these to you.\n" +
                    "• Always-on wake word in the background. Use the orb, the notification's Talk button, or the dashboard.\n" +
                    "• Placing calls or sending SMS silently. NYRO prepares them and taps Send/Call only after you confirm.\n" +
                    "• Apps that draw their UI without accessibility info expose little structure; NYRO falls back to screenshots.",
                color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
            )
        }
        Gap(12)
        Text("NYRO ${com.nyro.companion.BuildConfig.VERSION_NAME} · No account · No backend", color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(22.dp)).clickable(onClick = onClick), padding = 14) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                Text(sub, color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Nyro.TextLow)
        }
    }
}

@Composable
private fun Toggle(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
            Text(sub, color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.width(10.dp))
        Switch(value, onChange, colors = SwitchDefaults.colors(checkedThumbColor = Nyro.Void, checkedTrackColor = Nyro.Cyan, uncheckedTrackColor = Nyro.Navy))
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(thumbColor = Nyro.Cyan, activeTrackColor = Nyro.Cyan, inactiveTrackColor = Nyro.Navy)

@Composable
private fun VoicePicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val voices = remember(open) { if (open) NyroApp.graph.voice.voices() else emptyList() }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Voice", color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) { Text(current.ifBlank { "System default" }.take(28)) }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem({ Text("System default") }, { onPick(""); open = false })
            voices.forEach { v ->
                DropdownMenuItem({ Text("${v.locale.displayName} · ${v.name}".take(48)) }, { onPick(v.name); open = false })
            }
        }
    }
}

@Composable
private fun LanguagePicker(current: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val langs = listOf("", "en-US", "en-GB", "en-IN", "hi-IN", "ta-IN", "te-IN", "kn-IN", "ml-IN", "bn-IN", "mr-IN", "fr-FR", "de-DE", "es-ES", "ar-SA")
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text("Recognition language", color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { open = true }) { Text(current.ifBlank { "Phone (${Locale.getDefault().toLanguageTag()})" }) }
        DropdownMenu(open, { open = false }) {
            langs.forEach { tag ->
                DropdownMenuItem({ Text(if (tag.isEmpty()) "Phone default" else Locale.forLanguageTag(tag).displayName + " ($tag)") }, { onPick(tag); open = false })
            }
        }
    }
}
