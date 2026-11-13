package com.nyro.companion.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.KeyboardVoice
import androidx.compose.material.icons.outlined.NorthEast
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.ui.Routes
import com.nyro.companion.ui.components.IconBubble
import com.nyro.companion.ui.components.ListRow
import com.nyro.companion.ui.components.NyroMascot
import com.nyro.companion.ui.components.RowDivider
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.components.StatusPill
import com.nyro.companion.ui.components.mascotColor
import com.nyro.companion.ui.components.mascotLabel
import com.nyro.companion.ui.components.mascotStateOf
import com.nyro.companion.ui.theme.CardShape
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import com.nyro.companion.voice.VoiceEngine
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
    val mascot = mascotStateOf(st, speaking)
    val busy = st.taskActive || speaking
    val talk = { if (busy) g.agent.stop() else { g.agent.startListening(); go(Routes.CHAT) } }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = PageMargin),
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("NYRO", style = MaterialTheme.typography.displaySmall, color = Nyro.TextHi, modifier = Modifier.weight(1f))
            StatusPill(mascotLabel(mascot), mascotColor(mascot))
        }
        Text("Your phone. Your intelligence.", style = MaterialTheme.typography.bodyMedium, color = Nyro.TextMid)
        Spacer(Modifier.height(18.dp))

        // Hero: the mascot in a card that glows in the color of what NYRO is doing.
        val glow by animateColorAsState(mascotColor(mascot), tween(600), label = "heroGlow")
        Column(
            Modifier.fillMaxWidth().clip(CardShape)
                .background(Nyro.Glass)
                .background(Brush.radialGradient(listOf(glow.copy(alpha = if (Nyro.isDark) 0.24f else 0.16f), Color.Transparent)))
                .border(1.dp, Nyro.GlassEdge, CardShape)
                .padding(bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NyroMascot(
                mascot, mic,
                Modifier.size(230.dp).clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { talk() },
            )
            Text(
                if (st.taskActive) st.request else "Tap NYRO to talk",
                style = MaterialTheme.typography.bodyMedium, color = Nyro.TextMid, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = talk,
                colors = if (busy) ButtonDefaults.buttonColors(containerColor = Nyro.Danger) else ButtonDefaults.buttonColors(),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                modifier = Modifier.height(48.dp),
            ) {
                Icon(if (busy) Icons.Default.Stop else Icons.Default.Mic, null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(if (busy) "Stop" else "Talk to NYRO")
            }
        }
        Spacer(Modifier.height(12.dp))

        GlassCard(Modifier.fillMaxWidth(), accent = if (active) Nyro.Blue.copy(alpha = 0.5f) else Nyro.GlassEdge, padding = 0) {
            ListRow(
                title = if (active) "NYRO Mode" else "Normal Mode",
                subtitle = if (active) "NYRO floats over every app. Tap it to talk, long-press to stop."
                else "No overlay, no microphone, no capture. Your phone is just your phone.",
                icon = Icons.Outlined.AutoAwesome,
                tint = if (active) Nyro.Blue else Nyro.TextMid,
                trailing = {
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
                    )
                },
            )
        }

        // Setup checklist until everything essential is ready
        val permsDone = perms.overlay && perms.accessibility && perms.mic
        AnimatedVisibility(!(aiReady && permsDone)) {
            Column {
                Spacer(Modifier.height(20.dp))
                SectionLabel("Get started")
                GlassCard(Modifier.fillMaxWidth(), padding = 0) {
                    Column {
                        SetupRow("Connect your Azure AI", if (aiReady) "Connected: ${settings.provider.deployment}" else "Endpoint, key and deployment", aiReady, Icons.Outlined.Cloud) { go(Routes.PROVIDER) }
                        RowDivider(70.dp)
                        val missing = listOfNotNull(
                            "overlay".takeIf { !perms.overlay }, "phone control".takeIf { !perms.accessibility }, "microphone".takeIf { !perms.mic },
                        )
                        SetupRow("Grant permissions", if (missing.isEmpty()) "All set" else "Missing: ${missing.joinToString(", ")}", permsDone, Icons.Outlined.VerifiedUser) { go(Routes.PERMISSIONS) }
                        RowDivider(70.dp)
                        SetupRow("Turn on NYRO Mode", if (active) "On" else "Use the switch above", active, Icons.Outlined.AutoAwesome, null)
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel("Status")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatusTile(Icons.Outlined.Cloud, "AI", if (aiReady) "Azure" else "Local only", aiReady, Modifier.weight(1f))
            StatusTile(Icons.Outlined.TouchApp, "Control", if (perms.accessibility) "On" else "Off", perms.accessibility, Modifier.weight(1f))
            StatusTile(
                Icons.Outlined.KeyboardVoice, "Voice",
                if (!perms.mic) "No mic" else when (g.voice.recognizerKind()) {
                    VoiceEngine.RecognizerKind.ON_DEVICE -> "On-device"
                    VoiceEngine.RecognizerKind.NETWORK_FALLBACK -> "Fallback"
                    else -> "Type only"
                },
                perms.mic, Modifier.weight(1f),
            )
        }

        // Chat and Settings are tabs in the bottom bar, so they are not repeated here.
        Spacer(Modifier.height(20.dp))
        SectionLabel("Explore")
        val tiles = listOf(
            Tile(Icons.Outlined.TaskAlt, "Tasks", "What NYRO did", Routes.TASKS),
            Tile(Icons.Outlined.Smartphone, "Device", "Battery, storage, network", Routes.DEVICE),
            Tile(Icons.Outlined.History, "History", "Past conversations", Routes.HISTORY),
            Tile(Icons.Outlined.Psychology, "Memory", "What NYRO remembers", Routes.MEMORY),
            Tile(Icons.Outlined.Cloud, "AI Provider", "Your Azure connection", Routes.PROVIDER),
            Tile(Icons.Outlined.VerifiedUser, "Permissions", "What NYRO may use", Routes.PERMISSIONS),
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { tile -> TileCard(tile, Modifier.weight(1f)) { go(tile.route) } }
            }
        }
        Spacer(Modifier.height(24.dp + LocalBottomInset.current))
    }
}

@Composable
private fun SetupRow(title: String, sub: String, done: Boolean, icon: ImageVector, onClick: (() -> Unit)?) {
    ListRow(
        title = title, subtitle = sub,
        icon = if (done) Icons.Outlined.CheckCircle else icon,
        tint = if (done) Nyro.Ok else Nyro.Blue,
        onClick = onClick,
    )
}

@Composable
private fun StatusTile(icon: ImageVector, label: String, value: String, ok: Boolean, modifier: Modifier) {
    GlassCard(modifier, padding = 12) {
        Column {
            IconBubble(icon, if (ok) Nyro.Ok else Nyro.Amber, size = 32.dp)
            Spacer(Modifier.height(10.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = Nyro.TextMid)
            Text(value, style = MaterialTheme.typography.titleSmall, color = Nyro.TextHi, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private class Tile(val icon: ImageVector, val label: String, val sub: String, val route: String)

@Composable
private fun TileCard(tile: Tile, modifier: Modifier, onClick: () -> Unit) {
    GlassCard(modifier.clip(CardShape).clickable(onClick = onClick), padding = 16) {
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                IconBubble(tile.icon)
                Spacer(Modifier.weight(1f))
                Icon(Icons.Outlined.NorthEast, null, tint = Nyro.TextLow, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(tile.label, style = MaterialTheme.typography.titleMedium, color = Nyro.TextHi)
            Text(tile.sub, style = MaterialTheme.typography.bodySmall, color = Nyro.TextMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
