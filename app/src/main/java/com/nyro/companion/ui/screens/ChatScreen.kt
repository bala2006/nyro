package com.nyro.companion.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.agent.Phase
import com.nyro.companion.data.MessageEntity
import com.nyro.companion.overlay.RoundAction
import com.nyro.companion.ui.components.ChatInput
import com.nyro.companion.ui.components.ConfirmationCard
import com.nyro.companion.ui.components.MascotState
import com.nyro.companion.ui.components.NyroMascot
import com.nyro.companion.ui.components.mascotColor
import com.nyro.companion.ui.components.StepList
import com.nyro.companion.ui.components.mascotLabel
import com.nyro.companion.ui.components.mascotStateOf
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.flow.flowOf

/** Chat is a top-level tab, so its header has no back action. */
@Composable
fun ChatScreen(go: (String) -> Unit) {
    val g = NyroApp.graph
    val cid by g.agent.conversationId.collectAsStateWithLifecycle()
    val messagesFlow = remember(cid) { cid?.let { g.dao.messages(it) } ?: flowOf(emptyList()) }
    val dbMessages by messagesFlow.collectAsStateWithLifecycle(emptyList())
    val priv by g.agent.privateSession.collectAsStateWithLifecycle()
    val privMessages by g.agent.privateMessages.collectAsStateWithLifecycle()
    val paused by g.agent.paused.collectAsStateWithLifecycle()
    val messages = if (priv) privMessages else dbMessages
    val st by g.agent.state.collectAsStateWithLifecycle()
    val speaking by g.voice.speaking.collectAsStateWithLifecycle()
    val mic by g.voice.micLevel.collectAsStateWithLifecycle()
    val orb = mascotStateOf(st, speaking)
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val live = st.taskActive || st.phase == Phase.LISTENING || st.confirmation != null || st.phase == Phase.ERROR
    LaunchedEffect(messages.size, st.reply.length, st.steps.size, live) {
        val count = messages.size + if (live) 1 else 0
        if (count > 0) list.animateScrollToItem(count - 1)
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = PageMargin, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Chat", style = MaterialTheme.typography.displaySmall, color = Nyro.TextHi)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    NyroMascot(orb, mic, Modifier.size(40.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(mascotLabel(orb) + if (st.localOnly) " · local mode" else "", style = MaterialTheme.typography.labelMedium, color = mascotColor(orb))
                }
            }
            if (st.taskActive) IconButton(onClick = { g.agent.togglePause() }) {
                Icon(if (paused) Icons.Default.PlayArrow else Icons.Default.Pause, if (paused) "Resume" else "Pause", tint = Nyro.TextMid)
            }
            IconButton(onClick = { if (priv) g.agent.endPrivateSession() else g.agent.startPrivateSession() }) {
                Icon(Icons.Default.Lock, if (priv) "End private session" else "Start private session", tint = if (priv) Nyro.Amber else Nyro.TextMid)
            }
            IconButton(onClick = { g.agent.newConversation() }) { Icon(Icons.Default.Add, "New conversation", tint = Nyro.TextMid) }
        }

        if (priv) Text(
            "Private session: nothing from this chat is saved to history, tasks or memory.",
            color = Nyro.Amber, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = PageMargin),
        )
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = PageMargin), state = list,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (messages.isEmpty() && !live) item { EmptyHint(orb) { input = it } }
            items(messages, key = { it.id }) { m -> Bubble(m) }
            if (live) item(key = "live") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (st.phase == Phase.LISTENING) Bubble(MessageEntity(0, 0, "user", st.partialTranscript.ifBlank { "Listening…" }))
                    st.confirmation?.let { c -> ConfirmationCard(c.risk, c.text, { g.agent.answerConfirmation(true) }, { g.agent.answerConfirmation(false) }) }
                    if (st.steps.isNotEmpty()) GlassCard(Modifier.fillMaxWidth(), padding = 12) { StepList(st.steps) }
                    if (st.reply.isNotBlank() && st.taskActive) Bubble(MessageEntity(0, 0, "assistant", st.reply))
                    if (st.phase == Phase.ERROR && st.error != null) Text(st.error!!, color = Nyro.Danger, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(start = PageMargin, end = PageMargin, top = 4.dp, bottom = 12.dp + LocalBottomInset.current),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatInput(input, { input = it }, onSend = { if (input.isNotBlank()) { g.agent.submit(input); input = "" } }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            if (st.taskActive || speaking) RoundAction(Icons.Default.Stop, "Stop", Nyro.Danger) { g.agent.stop() }
            else RoundAction(Icons.Default.Mic, "Talk", if (st.phase == Phase.LISTENING) Nyro.Cyan else Nyro.Blue) { g.agent.startListening() }
        }
    }
}

/** Empty conversation: the mascot and a few example requests. Tapping one puts it in the input to edit or send. */
@Composable
private fun EmptyHint(state: MascotState, onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        NyroMascot(state, modifier = Modifier.size(150.dp))
        Text("What should we do?", style = MaterialTheme.typography.headlineSmall, color = Nyro.TextHi)
        Text("Ask a question or tell NYRO what to do on your phone.", style = MaterialTheme.typography.bodyMedium, color = Nyro.TextMid, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
        listOf(
            "How hot is my battery right now?",
            "Open YouTube and search for Android AI agent tutorials",
            "What's on my screen?",
            "Turn on the flashlight",
            "Summarize my notifications",
        ).forEach { s ->
            Text(
                s, style = MaterialTheme.typography.bodyMedium, color = Nyro.TextHi,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp))
                    .background(Nyro.Glass).border(1.dp, Nyro.GlassEdge, RoundedCornerShape(16.dp))
                    .clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
            )
        }
    }
}

@Composable
private fun Bubble(m: MessageEntity) {
    val user = m.role == "user"
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Box(
            Modifier.widthIn(max = 320.dp)
                .clip(if (user) RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp) else RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp))
                .background(if (user) Nyro.Blue else Nyro.Glass)
                .then(if (user) Modifier else Modifier.border(1.dp, Nyro.GlassEdge, RoundedCornerShape(18.dp, 18.dp, 18.dp, 6.dp)))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                m.content, style = MaterialTheme.typography.bodyLarge,
                color = when { user -> Nyro.OnPrimary; m.content.startsWith("⚠") -> Nyro.Danger; else -> Nyro.TextHi },
            )
        }
    }
}
