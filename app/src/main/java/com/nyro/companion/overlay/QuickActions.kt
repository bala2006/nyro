package com.nyro.companion.overlay

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
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

/** Quick action row shown on long-press of the orb. Closes after one choice or 8 seconds. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QuickActions(onDone: () -> Unit) {
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val undo by g.agent.undoLabel.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val pkg = NyroAccessibilityService.current?.foregroundPackage.orEmpty()
    val app = if (pkg.isBlank()) "Home" else g.apps.label(pkg)

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nyro.Glass.copy(alpha = 0.97f))
            .border(1.dp, Nyro.Cyan.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text("QUICK · $app", style = MaterialTheme.typography.labelSmall, color = Nyro.Cyan)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistChip(onClick = { g.agent.startListening(); onDone() }, label = { Text("Talk") })
            AssistChip(onClick = { g.agent.submit("What is on my screen right now? Describe it briefly."); onDone() }, label = { Text("What's on screen?") })
            AssistChip(onClick = { g.agent.submit("Explain the error or warning shown on my screen, and what to do about it."); onDone() }, label = { Text("Explain this error") })
            AssistChip(onClick = { g.agent.submit("Translate the visible text on my screen into English. List only the translation."); onDone() }, label = { Text("Translate screen") })
            if (com.nyro.companion.access.NyroNotificationListener.instance != null) {
                AssistChip(onClick = { g.agent.submit("Summarize my current notifications in two or three sentences."); onDone() }, label = { Text("Summarize notifications") })
            }
            undo?.let { label -> AssistChip(onClick = { scope.launch { g.agent.undoLast() }; onDone() }, label = { Text("Undo: $label") }) }
            if (st.taskActive) AssistChip(onClick = { g.agent.stop(); onDone() }, label = { Text("Stop") })
            suggestionsFor(pkg).forEach { text -> AssistChip(onClick = { g.agent.submit(text); onDone() }, label = { Text(text) }) }
        }
    }
}
