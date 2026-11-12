package com.nyro.companion.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.PowerSettingsNew
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.ui.components.ChatInput
import com.nyro.companion.ui.components.ConfirmationCard
import com.nyro.companion.ui.components.NyroOrb
import com.nyro.companion.ui.components.StepList
import com.nyro.companion.ui.components.orbLabel
import com.nyro.companion.ui.components.orbStateOf
import com.nyro.companion.ui.theme.Nyro

/**
 * Gesture handling uses raw screen coordinates: the window itself moves while dragging, so
 * view-local deltas would fight the movement.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun OrbOverlay(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDragBy: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onStop: () -> Unit,
    orbOnRight: () -> Boolean,
) {
    val view = androidx.compose.ui.platform.LocalView.current
    val slop = remember { android.view.ViewConfiguration.get(view.context).scaledTouchSlop.toFloat() }
    val gesture = remember { OrbGesture() }
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val speaking by g.voice.speaking.collectAsStateWithLifecycle()
    val mic by g.voice.micLevel.collectAsStateWithLifecycle()
    val orb = orbStateOf(st, speaking)
    val busy = st.taskActive || speaking || st.phase == com.nyro.companion.agent.Phase.LISTENING

    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        NyroOrb(
            orb, mic,
            Modifier
                .size(72.dp)
                .pointerInteropFilter { ev ->
                    when (ev.actionMasked) {
                        android.view.MotionEvent.ACTION_DOWN -> {
                            gesture.down(ev.rawX, ev.rawY)
                            view.postDelayed(gesture.longPressCheck { onLongPress(); view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS) },
                                android.view.ViewConfiguration.getLongPressTimeout().toLong())
                        }
                        android.view.MotionEvent.ACTION_MOVE -> {
                            val dx = ev.rawX - gesture.x0; val dy = ev.rawY - gesture.y0
                            if (!gesture.dragging && !gesture.longFired && (dx * dx + dy * dy) > slop * slop) { gesture.dragging = true; onDragStart() }
                            if (gesture.dragging) onDragBy(dx, dy)
                        }
                        android.view.MotionEvent.ACTION_UP -> {
                            gesture.up()
                            if (gesture.dragging) onDragEnd() else if (!gesture.longFired) { view.performClick(); onTap() }
                        }
                        android.view.MotionEvent.ACTION_CANCEL -> { gesture.up(); if (gesture.dragging) onDragEnd() }
                    }
                    true
                },
        )
        AnimatedVisibility(
            busy, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut(),
            modifier = Modifier.align(if (orbOnRight()) Alignment.TopStart else Alignment.TopEnd),
        ) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Nyro.Danger).border(1.5.dp, Color.White.copy(alpha = 0.8f), CircleShape)
                    .clickable { onStop() },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Default.Close, "Stop", tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
fun PanelOverlay(onCollapse: () -> Unit, onOpenApp: () -> Unit, onNormalMode: () -> Unit) {
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val speaking by g.voice.speaking.collectAsStateWithLifecycle()
    val mic by g.voice.micLevel.collectAsStateWithLifecycle()
    val orb = orbStateOf(st, speaking)
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.62f).dp
    var input by remember { mutableStateOf("") }
    val scroll = rememberScrollState()
    LaunchedEffect(st.reply.length, st.steps.size) { scroll.animateScrollTo(scroll.maxValue) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .navigationBarsPadding()
            .imePadding()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(Brush.verticalGradient(listOf(Color(0xF20B1630), Color(0xF7050810))))
            .border(1.dp, Brush.verticalGradient(listOf(Nyro.Cyan.copy(alpha = 0.45f), Nyro.Blue.copy(alpha = 0.08f))), RoundedCornerShape(28.dp))
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NyroOrb(orb, mic, Modifier.size(40.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("NYRO", style = MaterialTheme.typography.titleMedium, color = Nyro.TextHi)
                Text(
                    orbLabel(orb) + if (st.localOnly) " · local mode" else "",
                    style = MaterialTheme.typography.labelSmall, color = Nyro.Cyan.copy(alpha = 0.85f),
                )
            }
            IconButton(onClick = onOpenApp) { Icon(Icons.Default.OpenInNew, "Open NYRO", tint = Nyro.TextMid) }
            IconButton(onClick = onNormalMode) { Icon(Icons.Default.PowerSettingsNew, "Switch to Normal Mode", tint = Nyro.TextMid) }
            IconButton(onClick = onCollapse) { Icon(Icons.Default.KeyboardArrowDown, "Collapse", tint = Nyro.TextMid) }
        }

        st.confirmation?.let { c ->
            Spacer(Modifier.size(8.dp))
            ConfirmationCard(c.risk, c.text, onAllow = { g.agent.answerConfirmation(true) }, onDeny = { g.agent.answerConfirmation(false) })
        }

        Column(Modifier.heightIn(max = maxH).verticalScroll(scroll).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val heard = if (st.phase == com.nyro.companion.agent.Phase.LISTENING) st.partialTranscript else st.request
            if (heard.isNotBlank()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Text(
                        heard, color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)).background(Nyro.Blue.copy(alpha = 0.25f)).padding(10.dp, 8.dp),
                    )
                }
            } else if (st.phase == com.nyro.companion.agent.Phase.LISTENING) {
                Text("Listening…", color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium)
            } else if (st.reply.isBlank() && st.steps.isEmpty()) {
                Text("Tap the mic and speak, or type a request. Try “Open YouTube and search for Android AI agents”.", color = Nyro.TextLow, style = MaterialTheme.typography.bodyMedium)
            }
            if (st.steps.isNotEmpty()) StepList(st.steps.takeLast(8))
            if (st.reply.isNotBlank()) Text(st.reply, color = Nyro.TextHi, style = MaterialTheme.typography.bodyLarge)
            st.error?.let { if (it != st.reply) Text(it, color = Nyro.Danger, style = MaterialTheme.typography.bodySmall) }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            ChatInput(
                value = input, onValueChange = { input = it },
                onSend = { if (input.isNotBlank()) { g.agent.submit(input); input = "" } },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            if (st.taskActive || speaking) {
                RoundAction(Icons.Default.Stop, "Stop", Nyro.Danger) { g.agent.stop() }
            } else {
                RoundAction(Icons.Default.Mic, "Talk", if (st.phase == com.nyro.companion.agent.Phase.LISTENING) Nyro.Cyan else Nyro.Blue) { g.agent.startListening() }
            }
        }
    }
}

@Composable
fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).background(Brush.radialGradient(listOf(color, color.copy(alpha = 0.55f)))).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White) }
}


private class OrbGesture {
    var x0 = 0f; var y0 = 0f
    var dragging = false; var longFired = false; private var token = 0
    fun down(x: Float, y: Float) { x0 = x; y0 = y; dragging = false; longFired = false; token++ }
    fun up() { token++ }
    fun longPressCheck(action: () -> Unit): Runnable {
        val t = token
        return Runnable { if (t == token && !dragging) { longFired = true; action() } }
    }
}
