package com.nyro.companion.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.ui.Routes
import com.nyro.companion.ui.components.NyroOrb
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.components.orbLabel
import com.nyro.companion.ui.components.orbStateOf
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.launch

@Composable
fun DashboardScreen(go: (String) -> Unit) {
    val g = NyroApp.graph
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val active by g.mode.active.collectAsStateWithLifecycle()
    val st by g.agent.state.collectAsStateWithLifecycle()
    val speaking by g.voice.speaking.collectAsStateWithLifecycle()
    val mic by g.voice.micLevel.collectAsStateWithLifecycle()
    val perms = rememberPerms()
    val aiReady = settings.provider.isComplete && g.keys.hasApiKey()
    val orb = orbStateOf(st, speaking)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NYRO", style = MaterialTheme.typography.displaySmall, color = Nyro.TextHi, modifier = Modifier.weight(1f))
            Icon(Icons.Default.Settings, "Settings", tint = Nyro.TextMid, modifier = Modifier.clip(CircleShape).clickable { go(Routes.SETTINGS) }.padding(8.dp))
        }
        Text("Your phone. Your intelligence.", style = MaterialTheme.typography.bodyMedium, color = Nyro.TextMid, modifier = Modifier.fillMaxWidth())

        // Hero entity: tap to talk right here
        Box(Modifier.padding(vertical = 18.dp).size(220.dp), contentAlignment = Alignment.Center) {
            NyroOrb(orb, mic, Modifier.fillMaxSize().clip(CircleShape).clickable {
                if (st.taskActive || speaking) g.agent.stop() else { g.agent.startListening(); go(Routes.CHAT) }
            })
        }
        Text(orbLabel(orb).uppercase(), style = MaterialTheme.typography.labelSmall, color = Nyro.Cyan)
        Text(
            if (st.taskActive) st.request else "Tap the core to talk",
            style = MaterialTheme.typography.bodySmall, color = Nyro.TextLow, textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 2.dp, bottom = 18.dp),
        )

        // Mode switch
        GlassCard(Modifier.fillMaxWidth(), accent = if (active) Nyro.Cyan.copy(alpha = 0.55f) else Nyro.GlassEdge) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (active) "NYRO Mode" else "Normal Mode", style = MaterialTheme.typography.titleMedium, color = Nyro.TextHi)
                    Text(
                        if (active) "The companion floats over every app. Tap it to talk, long-press to stop."
                        else "No overlay, no microphone, no capture. Your phone is just your phone.",
                        style = MaterialTheme.typography.bodySmall, color = Nyro.TextMid,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = active,
                    onCheckedChange = { on ->
                        scope.launch {
                            if (on) {
                                if (!g.mode.enable()) {
                                    Toast.makeText(ctx, "Allow “Display over other apps” for NYRO, then switch on again.", Toast.LENGTH_LONG).show()
                                    runCatching { ctx.startActivity(g.mode.overlaySettingsIntent()) }
                                }
                            } else g.mode.disable()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedThumbColor = Nyro.Void, checkedTrackColor = Nyro.Cyan, uncheckedTrackColor = Nyro.Navy),
                )
            }
        }
        Spacer(Modifier.height(12.dp))

        // Setup checklist until everything essential is ready
        val setupDone = aiReady && perms.overlay && perms.accessibility && perms.mic
        AnimatedVisibility(!setupDone) {
            Column {
                SectionLabel("Get started")
                SetupStep(1, "Connect your Azure AI", if (aiReady) "Connected: ${settings.provider.deployment}" else "Endpoint, key and deployment", aiReady) { go(Routes.PROVIDER) }
                SetupStep(2, "Grant permissions", listOfNotNull(
                    "overlay".takeIf { !perms.overlay }, "phone control".takeIf { !perms.accessibility }, "microphone".takeIf { !perms.mic },
                ).joinToString(", ").ifEmpty { "All set" }.let { if (it == "All set") it else "Missing: $it" }, perms.overlay && perms.accessibility && perms.mic) { go(Routes.PERMISSIONS) }
                SetupStep(3, "Activate NYRO Mode", if (active) "On" else "Use the switch above", active) {}
                Spacer(Modifier.height(12.dp))
            }
        }

        SectionLabel("Status", Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusChip("AI", if (aiReady) "Azure" else "Local only", aiReady, Modifier.weight(1f))
            StatusChip("Control", if (perms.accessibility) "On" else "Off", perms.accessibility, Modifier.weight(1f))
            StatusChip("Voice", if (perms.mic) when (g.voice.recognizerKind()) {
                com.nyro.companion.voice.VoiceEngine.RecognizerKind.ON_DEVICE -> "On-device"
                com.nyro.companion.voice.VoiceEngine.RecognizerKind.NETWORK_FALLBACK -> "Fallback"
                else -> "Type only"
            } else "No mic", perms.mic, Modifier.weight(1f))
        }
        Spacer(Modifier.height(16.dp))

        SectionLabel("Explore", Modifier.fillMaxWidth())
        val tiles = listOf(
            Triple(Icons.AutoMirrored.Filled.Chat, "Conversation", Routes.CHAT),
            Triple(Icons.Default.TaskAlt, "Tasks", Routes.TASKS),
            Triple(Icons.Default.Smartphone, "Device", Routes.DEVICE),
            Triple(Icons.Default.History, "History", Routes.HISTORY),
            Triple(Icons.Default.Psychology, "Memory", Routes.MEMORY),
            Triple(Icons.Default.Cloud, "AI Provider", Routes.PROVIDER),
            Triple(Icons.Default.VerifiedUser, "Permissions", Routes.PERMISSIONS),
            Triple(Icons.Default.Tune, "Settings", Routes.SETTINGS),
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, label, route) -> Tile(icon, label, Modifier.weight(1f)) { go(route) } }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SetupStep(n: Int, title: String, sub: String, done: Boolean, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), padding = 14,
        accent = if (done) Nyro.Ok.copy(alpha = 0.35f) else Nyro.Cyan.copy(alpha = 0.35f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(30.dp).clip(CircleShape).background(if (done) Nyro.Ok.copy(alpha = 0.2f) else Nyro.Cyan.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Text(if (done) "✓" else "$n", color = if (done) Nyro.Ok else Nyro.Cyan, style = MaterialTheme.typography.titleSmall)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                Text(sub, color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Default.ChevronRight, null, tint = Nyro.TextLow)
        }
    }
}

@Composable
private fun StatusChip(label: String, value: String, ok: Boolean, modifier: Modifier) {
    GlassCard(modifier, padding = 12, accent = if (ok) Nyro.Cyan.copy(alpha = 0.3f) else Nyro.GlassEdge) {
        Column {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Nyro.TextLow)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(if (ok) Nyro.Ok else Nyro.Amber))
                Spacer(Modifier.width(6.dp))
                Text(value, style = MaterialTheme.typography.titleSmall, color = Nyro.TextHi, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick), padding = 16) {
        Column {
            Icon(icon, null, tint = Nyro.Cyan, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
    }
}
