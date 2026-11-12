package com.nyro.companion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.nyro.companion.data.MemoryEntity
import com.nyro.companion.data.Redactor
import com.nyro.companion.ui.components.ChatInput
import com.nyro.companion.ui.components.ConfirmationCard
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.components.StepList
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private fun fmt(t: Long) = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t))

@Composable
fun ConfirmClear(what: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Clear $what?") },
        text = { Text("This permanently deletes it from this phone.") },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text("Clear", color = Nyro.Danger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = Nyro.Navy,
    )
}

@Composable
fun TasksScreen(onBack: () -> Unit) {
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val tasks by g.dao.tasks().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmClear("task history", { scope.launch { g.dao.clearTasks() } }) { confirm = false }

    ScreenFrame("Tasks", onBack, actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Default.DeleteSweep, "Clear", tint = Nyro.TextMid) } }) {
        SectionLabel("Active")
        GlassCard(Modifier.fillMaxWidth(), accent = if (st.taskActive) Nyro.Cyan.copy(alpha = 0.5f) else Nyro.GlassEdge) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!st.taskActive && st.steps.isEmpty()) Text("No task running.", color = Nyro.TextMid)
                else {
                    Text(st.request, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                    st.confirmation?.let { c -> ConfirmationCard(c.risk, c.text, { g.agent.answerConfirmation(true) }, { g.agent.answerConfirmation(false) }) }
                    StepList(st.steps)
                    if (st.reply.isNotBlank()) Text(st.reply, color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium)
                    if (st.taskActive) Button(onClick = { g.agent.stop() }, colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = Nyro.Danger)) { Text("Stop task") }
                }
            }
        }
        Gap(18)
        SectionLabel("History (stored on this phone)")
        if (tasks.isEmpty()) Text("No tasks yet.", color = Nyro.TextLow)
        tasks.forEach { t ->
            var open by remember(t.id) { mutableStateOf(false) }
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(22.dp)).clickable { open = !open }, padding = 14) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val c = when (t.status) { "completed" -> Nyro.Ok; "failed" -> Nyro.Danger; "cancelled" -> Nyro.Amber; else -> Nyro.Cyan }
                        Text(t.status.uppercase(), color = c, style = MaterialTheme.typography.labelSmall)
                        Spacer(Modifier.width(8.dp))
                        Text(fmt(t.startedAt), color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(Redactor.redact(t.request), color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                    if (open) {
                        if (t.steps.isNotBlank()) Text(t.steps.trim(), color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                        if (t.summary.isNotBlank()) Text(t.summary, color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun HistoryScreen(onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val g = NyroApp.graph
    val convs by g.dao.conversations().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmClear("all conversations", {
        scope.launch { g.agent.newConversation(); g.dao.clearMessages(); g.dao.clearConversations() }
    }) { confirm = false }

    ScreenFrame("History", onBack, actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Default.DeleteSweep, "Clear all", tint = Nyro.TextMid) } }) {
        if (convs.isEmpty()) Text("No conversations yet. They are stored only on this phone.", color = Nyro.TextLow)
        convs.forEach { c ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(22.dp)).clickable { onOpen(c.id) }, padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title.ifBlank { "Conversation" }, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                        Text(fmt(c.updatedAt), color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = {
                        scope.launch {
                            if (g.agent.conversationId.value == c.id) g.agent.newConversation()
                            g.dao.deleteMessages(c.id); g.dao.deleteConversation(c.id)
                        }
                    }) { Icon(Icons.Default.Delete, "Delete", tint = Nyro.TextLow) }
                }
            }
        }
    }
}

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val g = NyroApp.graph
    val memories by g.dao.memories().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var warn by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    if (confirm) ConfirmClear("all memories", { scope.launch { g.dao.clearMemories() } }) { confirm = false }

    ScreenFrame("Memory", onBack, actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Default.DeleteSweep, "Clear all", tint = Nyro.TextMid) } }) {
        Text("Things NYRO knows about you. Stored only on this phone and included in requests to your Azure endpoint as context. Passwords, OTPs and card numbers are never saved.", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
        Gap(12)
        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatInput(input, { input = it; warn = null }, onSend = {
                val t = input.trim()
                if (t.isEmpty()) return@ChatInput
                if (Redactor.containsSecret(t)) { warn = "That looks like a password, code or card number. NYRO won't store it."; return@ChatInput }
                scope.launch { g.dao.insertMemory(MemoryEntity(content = t, source = "user")); input = "" }
            }, Modifier.weight(1f), placeholder = "e.g. I prefer short answers")
            IconButton(onClick = {
                val t = input.trim()
                if (t.isNotEmpty() && !Redactor.containsSecret(t)) scope.launch { g.dao.insertMemory(MemoryEntity(content = t, source = "user")); input = "" }
                else if (t.isNotEmpty()) warn = "That looks like a password, code or card number. NYRO won't store it."
            }) { Icon(Icons.Default.Add, "Add", tint = Nyro.Cyan) }
        }
        warn?.let { Text(it, color = Nyro.Amber, style = MaterialTheme.typography.bodySmall) }
        Gap(12)
        if (memories.isEmpty()) Text("No memories yet. Say “Remember that…” or add one above.", color = Nyro.TextLow)
        memories.forEach { m ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.content, color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                        Text("#${m.id} · ${if (m.source == "user") "added by you" else "learned by NYRO"} · ${fmt(m.createdAt)}", color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { scope.launch { g.dao.deleteMemory(m.id) } }) { Icon(Icons.Default.Delete, "Delete", tint = Nyro.TextLow, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}
