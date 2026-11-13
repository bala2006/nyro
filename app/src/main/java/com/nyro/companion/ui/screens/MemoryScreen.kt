package com.nyro.companion.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.data.MemoryEntity
import com.nyro.companion.data.MessageEntity
import com.nyro.companion.data.Redactor
import com.nyro.companion.ui.components.ChatInput
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.text.DateFormat
import java.util.Date

/** One memory in an exported file. Only these fields are written. */
@Serializable
data class MemoryDto(val content: String, val source: String = "user", val createdAt: Long = 0L)

private val memoryJson = Json { prettyPrint = true; ignoreUnknownKeys = true }

/**
 * Suggests memories from the user's own messages with simple patterns, such as "my name is" or
 * "I prefer". Nothing is saved until the user taps Save. Messages with sensitive text are skipped.
 */
object MemorySuggestions {
    private val patterns = listOf(
        Regex("(?i)\\bmy name is [A-Za-z][A-Za-z ]{1,30}"),
        Regex("(?i)\\bI prefer [^.!?\\n]{3,80}"),
        Regex("(?i)\\bI (?:really )?like [^.!?\\n]{3,80}"),
        Regex("(?i)\\bI live in [^.!?\\n]{2,60}"),
        Regex("(?i)\\bremember that [^.!?\\n]{3,120}"),
        Regex("(?i)\\bmy (?:wife|husband|partner|mom|dad|brother|sister|friend) is [^.!?\\n]{2,60}"),
    )

    fun from(messages: List<MessageEntity>, existing: List<String>, dismissed: Set<String>): List<String> {
        val out = LinkedHashSet<String>()
        for (m in messages) {
            if (Redactor.containsSecret(m.content)) continue
            for (p in patterns) {
                val fact = p.find(m.content)?.value?.trim() ?: continue
                if (existing.none { it.equals(fact, true) } && dismissed.none { it.equals(fact, true) }) out += fact
            }
        }
        return out.take(5)
    }
}

@Composable
fun MemoryScreen(onBack: () -> Unit) {
    val g = NyroApp.graph
    val ctx = LocalContext.current
    val memories by g.dao.memories().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("") }
    var warn by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var found by remember { mutableStateOf<Pair<List<MemoryEntity>, List<MessageEntity>>?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var dismissed by remember { mutableStateOf(setOf<String>()) }
    var recent by remember { mutableStateOf<List<MessageEntity>>(emptyList()) }
    if (confirm) ConfirmClear("all memories", { scope.launch { g.dao.clearMemories() } }) { confirm = false }

    LaunchedEffect(memories.size) { recent = g.dao.recentUserMessages(80) }
    val suggestions = MemorySuggestions.from(recent, memories.map { it.content }, dismissed)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val list = g.dao.memoryList(10_000).map { MemoryDto(it.content, it.source, it.createdAt) }
            status = runCatching {
                ctx.contentResolver.openOutputStream(uri)!!.use { out ->
                    out.write(memoryJson.encodeToString(ListSerializer(MemoryDto.serializer()), list).toByteArray())
                }
                "Exported ${list.size} memories."
            }.getOrElse { "Export failed: ${it.message}" }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            status = runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                val items = memoryJson.decodeFromString(ListSerializer(MemoryDto.serializer()), text)
                var saved = 0
                var skipped = 0
                items.forEach { m ->
                    val c = m.content.trim().take(500)
                    if (c.isEmpty() || Redactor.containsSecret(c)) {
                        skipped++
                    } else {
                        g.dao.insertMemory(MemoryEntity(content = c, source = m.source.take(20).ifBlank { "user" }))
                        saved++
                    }
                }
                "Imported $saved memories" + if (skipped > 0) ", skipped $skipped (empty or sensitive)." else "."
            }.getOrElse { "Import failed: this is not a NYRO memory file." }
        }
    }

    ScreenFrame("Memory", onBack, actions = { IconButton(onClick = { confirm = true }) { Icon(Icons.Default.DeleteSweep, "Clear all", tint = Nyro.TextMid) } }) {
        Text("Things NYRO knows about you. Stored only on this phone and sent to Azure as context. Passwords, codes and card numbers are never saved.", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
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

        if (suggestions.isNotEmpty()) {
            Gap(12)
            SectionLabel("NYRO noticed. Save?")
            suggestions.forEach { fact ->
                GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 12) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("\"$fact\"", color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { scope.launch { g.dao.insertMemory(MemoryEntity(content = fact, source = "user")) } }) { Text("Save", color = Nyro.Cyan) }
                            TextButton(onClick = { dismissed = dismissed + fact }) { Text("Dismiss", color = Nyro.TextMid) }
                        }
                    }
                }
            }
        }

        Gap(12)
        SectionLabel("Search memories and conversations")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, placeholder = { Text("Search", color = Nyro.TextLow) }, singleLine = true, modifier = Modifier.weight(1f), colors = fieldColors())
            TextButton(onClick = {
                val q = query.trim()
                if (q.length >= 2) scope.launch { found = g.dao.searchMemories(q) to g.dao.searchMessages(q) } else found = null
            }) { Text("Search", color = Nyro.Cyan) }
        }
        found?.let { (mems, msgs) ->
            if (mems.isEmpty() && msgs.isEmpty()) Text("Nothing found.", color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
            mems.forEach { Text("Memory: ${it.content}", color = Nyro.TextHi, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
            msgs.forEach { Text("${it.role}: ${it.content.take(160)}", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
        }

        Gap(12)
        SectionLabel("Import and export")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { exportLauncher.launch("nyro-memories.json") }) { Text("Export") }
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain")) }) { Text("Import") }
        }
        status?.let { Text(it, color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }

        Gap(12)
        SectionLabel("Saved memories")
        if (memories.isEmpty()) Text("No memories yet. Say “Remember that…” or add one above.", color = Nyro.TextLow)
        memories.forEach { m ->
            GlassCard(Modifier.fillMaxWidth().padding(bottom = 8.dp), padding = 14) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(m.content, color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                        Text("#${m.id} · ${if (m.source == "user") "added by you" else "learned by NYRO"} · ${DateFormat.getDateInstance().format(Date(m.createdAt))}", color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { scope.launch { g.dao.deleteMemory(m.id) } }) { Icon(Icons.Default.Delete, "Delete", tint = Nyro.TextLow, modifier = Modifier.size(20.dp)) }
                }
            }
        }
    }
}
