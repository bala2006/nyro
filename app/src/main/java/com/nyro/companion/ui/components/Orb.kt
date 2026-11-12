package com.nyro.companion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import com.nyro.companion.agent.AssistantState
import com.nyro.companion.agent.Phase
import com.nyro.companion.ui.theme.Nyro
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, EXECUTING, ERROR, ATTENTION, HAPPY, CONFUSED }

fun orbStateOf(s: AssistantState, speaking: Boolean): OrbState = when {
    s.confirmation != null -> OrbState.ATTENTION
    s.phase == Phase.ERROR -> OrbState.ERROR
    s.phase == Phase.LISTENING -> OrbState.LISTENING
    s.phase == Phase.EXECUTING -> OrbState.EXECUTING
    speaking -> OrbState.SPEAKING
    s.phase == Phase.THINKING -> OrbState.THINKING
    else -> OrbState.IDLE
}

fun orbLabel(s: OrbState) = when (s) {
    OrbState.IDLE -> "Ready"; OrbState.LISTENING -> "Listening"; OrbState.THINKING -> "Thinking"
    OrbState.SPEAKING -> "Speaking"; OrbState.EXECUTING -> "Working"; OrbState.ERROR -> "Problem"; OrbState.ATTENTION -> "Needs you"; OrbState.HAPPY -> "Done"; OrbState.CONFUSED -> "Not sure"
}

private fun accentOf(s: OrbState) = when (s) {
    OrbState.IDLE -> Nyro.Cyan; OrbState.LISTENING -> Nyro.Cyan; OrbState.THINKING -> Nyro.Violet
    OrbState.SPEAKING -> Nyro.Teal; OrbState.EXECUTING -> Nyro.Blue; OrbState.ERROR -> Nyro.Danger; OrbState.ATTENTION -> Nyro.Amber
    OrbState.HAPPY -> Nyro.Ok; OrbState.CONFUSED -> Nyro.Terracotta
}

/**
 * The NYRO entity: a glass energy core with orbiting light. It is static while idle (no frames
 * are drawn), and animates only while listening, thinking, speaking, working or asking.
 */
@Composable
fun NyroOrb(state: OrbState, micLevel: Float = 0f, modifier: Modifier = Modifier) {
    val accent by animateColorAsState(accentOf(state), tween(450), label = "accent")
    val level by animateFloatAsState(if (state == OrbState.LISTENING) micLevel else 0f, tween(90), label = "mic")
    if (state == OrbState.IDLE) {
        // Idle breathing: slow and light, so it costs little battery.
        val tr = rememberInfiniteTransition(label = "idle")
        val breathe by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3200), RepeatMode.Reverse), label = "breathe")
        Canvas(modifier) { drawOrb(state, accent, 0f, breathe, 0f) }
    } else {
        val tr = rememberInfiniteTransition(label = "orb")
        val spin by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == OrbState.EXECUTING) 1400 else 2600, easing = LinearEasing)), label = "spin")
        val pulse by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1300), RepeatMode.Reverse), label = "pulse")
        Canvas(modifier) { drawOrb(state, accent, spin, pulse, level) }
    }
}

private fun DrawScope.drawOrb(state: OrbState, accent: Color, spin: Float, pulse: Float, level: Float) {
    val c = center
    val r = size.minDimension / 2f
    val core = r * 0.56f * (1f + 0.06f * level + if (state == OrbState.ATTENTION) 0.04f * pulse else 0f)
    val shake = if (state == OrbState.ERROR) sin(spin * 2f * PI.toFloat() * 6f) * r * 0.03f else 0f
    val cc = Offset(c.x + shake, c.y)

    // Halo
    val haloA = when (state) { OrbState.IDLE -> 0.20f + 0.12f * pulse; OrbState.LISTENING -> 0.35f + 0.4f * level; else -> 0.3f + 0.2f * pulse }
    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = haloA), accent.copy(alpha = 0f)), cc, r), r, cc)

    // Listening: expanding rings driven by the voice
    if (state == OrbState.LISTENING) {
        for (i in 0 until 3) {
            val p = (spin + i / 3f) % 1f
            drawCircle(accent.copy(alpha = (1f - p) * (0.35f + 0.5f * level)), core + (r - core) * p, cc, style = Stroke(r * 0.025f))
        }
    }

    // Glass sphere
    drawCircle(
        Brush.radialGradient(
            0f to Nyro.Ice.copy(alpha = 0.95f), 0.28f to accent.copy(alpha = 0.9f), 0.75f to Nyro.Blue.copy(alpha = 0.6f), 1f to Nyro.Void,
            center = Offset(cc.x - core * 0.3f, cc.y - core * 0.35f), radius = core * 1.45f,
        ), core, cc,
    )
    // Inner energy swirl
    rotate(spin * 360f, cc) {
        drawArc(
            Brush.sweepGradient(listOf(Color.Transparent, Nyro.Ice.copy(alpha = 0.55f), Color.Transparent), cc),
            0f, 300f, false, Offset(cc.x - core * 0.62f, cc.y - core * 0.62f), Size(core * 1.24f, core * 1.24f), style = Stroke(core * 0.08f, cap = StrokeCap.Round),
        )
    }
    // Speaking: a living waveform around the core
    if (state == OrbState.SPEAKING) {
        val path = Path()
        val steps = 72
        for (i in 0..steps) {
            val a = i / steps.toFloat() * 2f * PI.toFloat()
            val wave = sin(a * 6f + spin * 2f * PI.toFloat() * 2f) * 0.5f + sin(a * 3f - spin * 2f * PI.toFloat()) * 0.5f
            val rr = core * (1.13f + 0.09f * wave * (0.5f + pulse))
            val p = Offset(cc.x + cos(a) * rr, cc.y + sin(a) * rr)
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(path, accent.copy(alpha = 0.85f), style = Stroke(r * 0.03f))
    }
    // Thinking / executing / attention: orbiting arcs
    if (state == OrbState.THINKING || state == OrbState.EXECUTING || state == OrbState.ATTENTION) {
        val orbitR = core * 1.28f
        val segs = if (state == OrbState.EXECUTING) 3 else 2
        for (i in 0 until segs) {
            rotate(spin * 360f * (if (i % 2 == 0) 1f else -0.7f) + i * (360f / segs), cc) {
                drawArc(
                    accent.copy(alpha = 0.9f), 0f, if (state == OrbState.EXECUTING) 70f else 110f, false,
                    Offset(cc.x - orbitR, cc.y - orbitR), Size(orbitR * 2, orbitR * 2), style = Stroke(r * 0.045f, cap = StrokeCap.Round),
                )
            }
        }
        // satellite
        val a = spin * 2f * PI.toFloat()
        drawCircle(Nyro.Ice, r * 0.035f, Offset(cc.x + cos(a) * orbitR, cc.y + sin(a) * orbitR))
    }
    // Idle: a thin static orbit line for presence
    if (state == OrbState.IDLE) drawCircle(accent.copy(alpha = 0.35f), core * 1.25f, cc, style = Stroke(r * 0.015f))
    // Specular highlight (glass)
    drawOval(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.55f), Color.White.copy(alpha = 0f)), startY = cc.y - core * 0.85f, endY = cc.y - core * 0.1f),
        topLeft = Offset(cc.x - core * 0.55f, cc.y - core * 0.82f), size = Size(core * 0.9f, core * 0.55f),
    )
    if (state == OrbState.HAPPY || state == OrbState.CONFUSED || state == OrbState.ERROR) drawFace(state, cc, core)
    // Rim
    drawCircle(Color.White.copy(alpha = 0.18f), core, cc, style = Stroke(r * 0.012f))
}

/** Small expression on the core: happy after a clean task, confused when a step failed, frown on error. */
private fun DrawScope.drawFace(state: OrbState, cc: Offset, core: Float) {
    val eye = Color.White.copy(alpha = 0.92f)
    val ex = core * 0.3f
    val ey = cc.y - core * 0.12f
    drawCircle(eye, core * 0.09f, Offset(cc.x - ex, ey))
    drawCircle(eye, core * 0.09f, Offset(cc.x + ex, ey))
    val my = cc.y + core * 0.26f
    val path = Path().apply {
        moveTo(cc.x - core * 0.3f, my)
        when (state) {
            OrbState.HAPPY -> quadraticTo(cc.x, my + core * 0.28f, cc.x + core * 0.3f, my)
            OrbState.ERROR -> quadraticTo(cc.x, my - core * 0.22f, cc.x + core * 0.3f, my)
            else -> {
                quadraticTo(cc.x - core * 0.15f, my - core * 0.12f, cc.x, my)
                quadraticTo(cc.x + core * 0.15f, my + core * 0.12f, cc.x + core * 0.3f, my)
            }
        }
    }
    drawPath(path, eye, style = Stroke(core * 0.07f, cap = StrokeCap.Round))
}
