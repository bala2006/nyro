package com.nyro.companion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.ui.Routes
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import com.nyro.companion.voice.VoiceEngine

private data class Capability(val name: String, val can: Boolean, val enables: String, val fix: String, val route: String? = null)

/** What NYRO can and cannot do right now, with the step that changes each answer. */
@Composable
fun CapabilitiesScreen(onBack: () -> Unit, go: (String) -> Unit) {
    val g = NyroApp.graph
    val p = rememberPerms()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val aiReady = settings.provider.isComplete && g.keys.hasApiKey()
    val voice = g.voice.recognizerKind()

    val items = listOf(
        Capability("Floating NYRO and status", p.overlay, "NYRO and its status pill over other apps.", "Allow “Display over other apps” in Permissions.", Routes.PERMISSIONS),
        Capability("Control the phone", p.accessibility, "Read the screen, tap, type, scroll, go back and home.", "Enable NYRO Phone Control in Permissions.", Routes.PERMISSIONS),
        Capability("Voice input", p.mic && voice != VoiceEngine.RecognizerKind.UNAVAILABLE, "Speak instead of typing.", if (!p.mic) "Grant the microphone in Permissions." else "On this phone, speech recognition isn't available. Enable the fallback in Settings → Voice.", Routes.PERMISSIONS),
        Capability("Azure AI reasoning", aiReady, "Plans and answers with your Azure deployment.", "Add the endpoint, key and deployment in AI Provider.", Routes.PROVIDER),
        Capability("Screenshots for vision", settings.allowScreenshots && aiReady, "Look at the screen when text isn't enough.", "Enable screenshots in Settings → Safety, and set up Azure.", Routes.SETTINGS),
        Capability("Notifications", p.notifListener, "Read and reply to notifications.", "Optional: grant notification access in Permissions.", Routes.PERMISSIONS),
        Capability("Photos, videos and audio", p.media, "Search, open and share your media files.", "Optional: grant media access in Permissions.", Routes.PERMISSIONS),
        Capability("Stay running in background", p.batteryUnrestricted, "Keeps NYRO Mode alive on TECNO/HiOS.", "Set battery to Unrestricted in Permissions.", Routes.PERMISSIONS),
        Capability("Works without Azure", true, "Device info, opening apps, flashlight, home and back.", "Always available. Other requests need Azure."),
    )

    ScreenFrame("What NYRO can access", onBack) {
        Text("A plain list. Tap a row that needs setup to fix it.", color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium)
        Gap(12)
        items.forEach { c ->
            GlassCard(
                Modifier.fillMaxWidth().padding(bottom = 8.dp),
                accent = if (c.can) Nyro.Ok.copy(alpha = 0.35f) else Nyro.Amber.copy(alpha = 0.5f),
                padding = 14,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(c.name, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (c.can) "Can" else "Cannot", color = if (c.can) Nyro.Ok else Nyro.Amber, style = MaterialTheme.typography.labelMedium)
                    }
                    Text(c.enables, color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                    if (!c.can) Text(c.fix, color = Nyro.TextHi, style = MaterialTheme.typography.bodySmall)
                    if (!c.can && c.route != null) {
                        Text("Open →", color = Nyro.Cyan, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { go(c.route) })
                    }
                }
            }
        }
        SectionLabel("Not possible on stock Android without root", Modifier.padding(top = 12.dp))
        GlassCard(Modifier.fillMaxWidth(), padding = 14) {
            Text(
                "Toggling Wi-Fi, Bluetooth or mobile data directly; reading secure screens such as banking or password fields; bypassing lock screens or permission prompts; an always-on wake word; silent calls or messages.",
                color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
