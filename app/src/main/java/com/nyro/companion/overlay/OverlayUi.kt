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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
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
    val mood by g.agent.mood.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val base = orbStateOf(st, speaking)
    // After a task the orb shows its mood briefly; otherwise it shows the live state.
    val orb = if (base == com.nyro.companion.ui.components.OrbState.IDLE && mood != null) {
        if (mood == "happy") com.nyro.companion.ui.components.OrbState.HAPPY else com.nyro.companion.ui.components.OrbState.CONFUSED
    } else base
    val busy = st.taskActive || speaking || st.phase == com.nyro.companion.agent.Phase.LISTENING
    val size = if (settings.orbCompact) 28.dp else 72.dp * settings.orbScale

    Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
        NyroOrb(
            orb, mic,
            Modifier
                .size(size)
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


/**
 * Approval card for risky actions, shown under the top status pill. It has no size when nothing
 * needs approval, so it doesn't block touches on the apps beneath.
 */
@Composable
fun ConfirmOverlay() {
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val c = st.confirmation ?: return
    val accent = if (c.risk == com.nyro.companion.tools.Risk.HIGH) Nyro.Danger else Nyro.Amber
    val app = com.nyro.companion.tools.ScreenSession.last?.appLabel?.takeIf { it.isNotBlank() }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Nyro.Glass.copy(alpha = 0.97f))
            .border(1.dp, accent.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Text(
            (if (c.risk == com.nyro.companion.tools.Risk.HIGH) "HIGH-RISK ACTION" else "CONFIRM ACTION") + (app?.let { " · $it" } ?: ""),
            style = MaterialTheme.typography.labelSmall, color = accent,
        )
        Text(c.text, style = MaterialTheme.typography.bodyMedium, color = Nyro.TextHi, maxLines = 6)
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { g.agent.answerConfirmation(false) }) { Text("Don't", color = Nyro.TextHi) }
            Button(
                onClick = { g.agent.answerConfirmation(true) },
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Nyro.Void),
            ) { Text("Allow") }
        }
    }
}
