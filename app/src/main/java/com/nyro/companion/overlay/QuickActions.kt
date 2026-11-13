package com.nyro.companion.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.NyroNotificationListener
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.launch

/** Commands suggested for the app in front. They only run when the user taps them. */
fun suggestionsFor(pkg: String): List<String> = when {
    pkg.contains("whatsapp") -> listOf("Read my latest WhatsApp messages", "Summarize this chat")
    pkg.contains("youtube") -> listOf("Search YouTube for something")
    pkg == "com.android.settings" -> listOf("Open Wi-Fi settings", "Turn on Bluetooth")
    pkg.contains("chrome") -> listOf("Summarize this page")
    pkg.contains("gm") || pkg.contains("gmail") -> listOf("Summarize my unread emails")
    else -> emptyList()
}

/**
 * Long-press menu for the companion: talk, stop and undo; the companion's own settings; NYRO Mode controls; and quick
 * questions. It closes after 20 seconds without a touch, and after any action that leaves the screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MascotMenu(
    onDone: () -> Unit,
    onInteract: () -> Unit,
    onHide: () -> Unit,
    onMoveToRest: () -> Unit,
    onOpenNyro: () -> Unit,
) {
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val undo by g.agent.undoLabel.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val pkg = NyroAccessibilityService.current?.foregroundPackage.orEmpty()
    val app = if (pkg.isBlank()) "Home" else g.apps.label(pkg)

    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(Nyro.Glass.copy(alpha = 0.98f))
            .border(1.dp, Nyro.Blue.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
            .pointerInput(Unit) { awaitPointerEventScope { while (true) { awaitPointerEvent(); onInteract() } } }
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
    ) {
        Text("NYRO · $app", style = MaterialTheme.typography.labelMedium, color = Nyro.TextMid)
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { g.agent.startListening(); onDone() }, label = { Text("Talk") })
            if (st.taskActive) AssistChip(onClick = { g.agent.stop(); onDone() }, label = { Text("Stop task") })
            undo?.let { label ->
                AssistChip(onClick = { scope.launch { g.agent.undoLast() }; onDone() }, label = { Text("Undo: $label") })
            }
        }

        SectionTitle("Companion")
        ToggleRow("Roam around the screen", settings.roamMascot) { v -> scope.launch { g.settings.setRoamMascot(v) } }
        ToggleRow("Compact dot", settings.orbCompact) { v ->
            scope.launch { g.settings.saveOrbLook(settings.orbScale, settings.orbOpacity, v, settings.dockEdges) }
        }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FilledTonalButton(onClick = { onMoveToRest(); onInteract() }) { Text("Rest in corner") }
            OutlinedButton(onClick = { onHide(); onDone() }) { Text("Hide for 1 hour") }
        }

        SectionTitle("NYRO Mode")
        ToggleRow("Speak replies", settings.speakReplies) { v -> scope.launch { g.settings.update { it.copy(speakReplies = v) } } }
        FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedButton(onClick = { onOpenNyro(); onDone() }) { Text("Open NYRO") }
            Button(
                onClick = { scope.launch { g.mode.disable() }; onDone() },
                colors = ButtonDefaults.buttonColors(containerColor = Nyro.Danger, contentColor = Color.White),
            ) { Text("Turn off NYRO Mode") }
        }

        SectionTitle("Ask NYRO")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { g.agent.submit("What is on my screen right now? Describe it briefly."); onDone() }, label = { Text("What's on screen?") })
            AssistChip(onClick = { g.agent.submit("Explain the error or warning shown on my screen, and what to do about it."); onDone() }, label = { Text("Explain this error") })
            AssistChip(onClick = { g.agent.submit("Translate the visible text on my screen into English. List only the translation."); onDone() }, label = { Text("Translate screen") })
            AssistChip(onClick = { g.agent.submit("Check my phone's health and tell me what to fix."); onDone() }, label = { Text("Check phone health") })
            if (NyroNotificationListener.instance != null) {
                AssistChip(onClick = { g.agent.submit("Summarize my current notifications in two or three sentences."); onDone() }, label = { Text("Summarize notifications") })
            }
            suggestionsFor(pkg).forEach { text -> AssistChip(onClick = { g.agent.submit(text); onDone() }, label = { Text(text) }) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = Nyro.Cyan, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = Nyro.TextHi, modifier = Modifier.weight(1f))
        Spacer(Modifier.padding(horizontal = 6.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
