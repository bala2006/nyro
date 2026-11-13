package com.nyro.companion.ui.components

import android.graphics.RuntimeShader
import android.os.PowerManager
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import com.nyro.companion.agent.AssistantState
import com.nyro.companion.agent.Phase
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** What NYRO is doing. Each state gives the mascot its own expression and motion. */
enum class MascotState { IDLE, LISTENING, THINKING, SPEAKING, EXECUTING, ERROR, ATTENTION, HAPPY, CONFUSED }

fun mascotStateOf(s: AssistantState, speaking: Boolean): MascotState = when {
    s.confirmation != null -> MascotState.ATTENTION
    s.phase == Phase.ERROR -> MascotState.ERROR
    s.phase == Phase.LISTENING -> MascotState.LISTENING
    s.phase == Phase.EXECUTING -> MascotState.EXECUTING
    speaking -> MascotState.SPEAKING
    s.phase == Phase.THINKING -> MascotState.THINKING
    else -> MascotState.IDLE
}

fun mascotLabel(s: MascotState) = when (s) {
    MascotState.IDLE -> "Ready"; MascotState.LISTENING -> "Listening"; MascotState.THINKING -> "Thinking"
    MascotState.SPEAKING -> "Speaking"; MascotState.EXECUTING -> "Working"; MascotState.ERROR -> "Problem"
    MascotState.ATTENTION -> "Needs you"; MascotState.HAPPY -> "Done"; MascotState.CONFUSED -> "Not sure"
}

/**
 * Resting expression for one state. The mascot springs between poses, so every change of state looks like a gesture.
 * Scale is squash/stretch, tip is how pointed the drop's top is, curve is +1 for happy arcs and -1 for sleepy arcs.
 */
private class Pose(
    val sx: Float, val sy: Float, val tip: Float, val wobble: Float, val wobbleSpeed: Float,
    val eyeOpen: Float, val eyeCurve: Float, val eyeAsym: Float,
    val mouthOpen: Float, val mouthCurve: Float, val blush: Float, val glow: Float, val bubbles: Float,
    val lookX: Float, val lookY: Float, val tilt: Float, val tint: Color, val deep: Color,
)

private val Awake = Pose(1f, 1f, 0.35f, 0.02f, 1.2f, 1f, 0f, 0f, 0f, 0.6f, 0.35f, 0.5f, 0.25f, 0f, 0f, 0f, Color(0xFF7CC8FF), Color(0xFF0E3E9E))
private val Asleep = Pose(1.08f, 0.9f, 0.18f, 0.012f, 0.6f, 0f, -1f, 0f, 0f, 0.25f, 0.55f, 0.22f, 0f, 0f, -0.35f, 0.12f, Color(0xFF6E9FDB), Color(0xFF13285E))

private fun poseOf(s: MascotState, sleeping: Boolean): Pose = when (s) {
    MascotState.IDLE -> if (sleeping) Asleep else Awake
    MascotState.LISTENING -> Pose(0.96f, 1.06f, 0.55f, 0.03f, 2f, 1.15f, 0f, 0f, 0.14f, 0.3f, 0.3f, 0.85f, 0.3f, 0f, 0.15f, 0f, Color(0xFF7FD6FF), Color(0xFF0B47A8))
    MascotState.THINKING -> Pose(1f, 1f, 0.45f, 0.025f, 1.5f, 0.72f, 0f, 0f, 0f, -0.1f, 0.15f, 0.6f, 0.9f, 0.7f, 0.6f, -0.12f, Color(0xFFA9A6FF), Color(0xFF2C2386))
    MascotState.SPEAKING -> Pose(1f, 1f, 0.4f, 0.03f, 2.2f, 0.95f, 0.2f, 0f, 0.05f, 0.5f, 0.35f, 0.85f, 0.25f, 0f, 0f, 0f, Color(0xFF7FE3F0), Color(0xFF0C4F8A))
    MascotState.EXECUTING -> Pose(1.02f, 0.98f, 0.5f, 0.04f, 3f, 0.8f, 0f, 0f, 0f, 0.2f, 0.2f, 0.9f, 1f, 0f, -0.1f, 0f, Color(0xFF6FB4FF), Color(0xFF0B3A9A))
    MascotState.ERROR -> Pose(1.04f, 0.94f, 0.2f, 0.05f, 4f, 0.9f, 0f, 0.3f, 0.25f, -0.8f, 0f, 0.5f, 0f, 0f, 0f, 0f, Color(0xFFFF9A8A), Color(0xFF8E1B22))
    MascotState.ATTENTION -> Pose(0.94f, 1.1f, 0.7f, 0.03f, 2f, 1.2f, 0f, 0f, 0.3f, 0f, 0.3f, 0.9f, 0.2f, 0f, 0.1f, 0f, Color(0xFFFFD27A), Color(0xFF8A4A06))
    MascotState.HAPPY -> Pose(1f, 1f, 0.4f, 0.03f, 2f, 0f, 1f, 0f, 0.4f, 1f, 0.8f, 0.9f, 0.3f, 0f, 0f, 0f, Color(0xFF8FF0D0), Color(0xFF0B6A6A))
    MascotState.CONFUSED -> Pose(1f, 1f, 0.35f, 0.025f, 1.4f, 0.8f, 0f, 0.6f, 0f, -0.3f, 0.2f, 0.5f, 0.2f, -0.4f, 0.3f, -0.25f, Color(0xFFFFB38A), Color(0xFF8A3A1A))
}

private class AnimPose(
    val sx: State<Float>, val sy: State<Float>, val tip: State<Float>, val wobble: State<Float>, val wobbleSpeed: State<Float>,
    val eyeOpen: State<Float>, val eyeCurve: State<Float>, val eyeAsym: State<Float>,
    val mouthOpen: State<Float>, val mouthCurve: State<Float>, val blush: State<Float>, val glow: State<Float>,
    val bubbles: State<Float>, val lookX: State<Float>, val lookY: State<Float>, val tilt: State<Float>,
    val tint: State<Color>, val deep: State<Color>,
)

@Composable
private fun animatePose(p: Pose): AnimPose {
    val s = spring<Float>(dampingRatio = 0.5f, stiffness = 200f)
    val c = tween<Color>(600)
    return AnimPose(
        animateFloatAsState(p.sx, s, label = "sx"), animateFloatAsState(p.sy, s, label = "sy"),
        animateFloatAsState(p.tip, s, label = "tip"), animateFloatAsState(p.wobble, s, label = "wobble"),
        animateFloatAsState(p.wobbleSpeed, tween(600), label = "wobbleSpeed"),
        animateFloatAsState(p.eyeOpen, s, label = "eyeOpen"), animateFloatAsState(p.eyeCurve, s, label = "eyeCurve"),
        animateFloatAsState(p.eyeAsym, s, label = "eyeAsym"), animateFloatAsState(p.mouthOpen, s, label = "mouthOpen"),
        animateFloatAsState(p.mouthCurve, s, label = "mouthCurve"), animateFloatAsState(p.blush, tween(500), label = "blush"),
        animateFloatAsState(p.glow, tween(500), label = "glow"), animateFloatAsState(p.bubbles, tween(800), label = "bubbles"),
        animateFloatAsState(p.lookX, s, label = "lookX"), animateFloatAsState(p.lookY, s, label = "lookY"),
        animateFloatAsState(p.tilt, s, label = "tilt"),
        animateColorAsState(p.tint, c, label = "tint"), animateColorAsState(p.deep, c, label = "deep"),
    )
}

private const val TAG = "NyroMascot"

/**
 * NYRO's mascot: a 3D-shaded liquid drop with a face. Idle it breathes, blinks and looks around, and after
 * [sleepAfterMs] without activity it falls asleep. Listening stretches with the voice, thinking glances up with bubbles,
 * speaking talks, working bounces, a problem shakes, needing you perks up, and done hops with squash and stretch.
 */
@Composable
fun NyroMascot(state: MascotState, micLevel: Float = 0f, modifier: Modifier = Modifier, sleepAfterMs: Long = 20_000, watch: Float = 0f) {
    var sleeping by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        sleeping = false
        if (state == MascotState.IDLE) {
            delay(sleepAfterMs)
            sleeping = true
        }
    }
    val pose = animatePose(poseOf(state, sleeping))
    val mic = animateFloatAsState(if (state == MascotState.LISTENING) micLevel.coerceIn(0f, 1f) else 0f, tween(90), label = "mic")
    val sleepAmt = animateFloatAsState(if (sleeping) 1f else 0f, tween(900), label = "sleep")

    // The clock is read only while drawing, so a frame redraws the canvas without recomposing anything.
    // Frame budget: full rate while NYRO is busy, about 24 fps idle, about 8 fps asleep, none while the screen is off.
    val time = remember { mutableFloatStateOf(0f) }
    val relaxed by rememberUpdatedState(state == MascotState.IDLE)
    val asleep by rememberUpdatedState(sleeping)
    val power = LocalContext.current.getSystemService(PowerManager::class.java)
    // Scrolled out of view or the screen is off: keep the last frame and stop drawing.
    var onScreen by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            if (power?.isInteractive == false || !onScreen) { delay(250); continue }
            withFrameNanos { now -> time.floatValue = (now - start) / 1_000_000_000f }
            when {
                asleep -> delay(110)
                relaxed -> delay(30)
            }
        }
    }
    val zColor = Nyro.TextHi
    val shader = remember {
        runCatching { RuntimeShader(MASCOT_SHADER) }.onFailure { Log.e(TAG, "Mascot shader failed to compile", it) }.getOrNull()
    }
    val brush = remember(shader) { shader?.let { ShaderBrush(it) } }

    // The offscreen layer caches the rendered drop: scrolling or redrawing the rest of the screen reuses its pixels,
    // and the shader runs again only when the mascot itself changes.
    Canvas(
        modifier
            .onGloballyPositioned { onScreen = it.boundsInWindow().let { b -> b.width > 0f && b.height > 0f } }
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen },
    ) {
        val m = motion(state, sleeping, sleepAmt.value, pose, mic.value, time.floatValue, watch)
        val tint = pose.tint.value
        if (state == MascotState.LISTENING) {
            for (i in 0 until 2) {
                val p = (m.t * 0.8f + i * 0.5f) % 1f
                drawCircle(
                    tint.copy(alpha = (1f - p) * (0.2f + 0.5f * mic.value)), m.r * (1.15f + 0.6f * p), Offset(m.cx, m.cy),
                    style = Stroke(m.r * 0.035f),
                )
            }
        }
        if (shader != null && brush != null && drawContext.canvas.nativeCanvas.isHardwareAccelerated) {
            shader.setUniforms(m, pose, tint)
            drawRect(brush)
        } else {
            drawFallback(m, tint, pose.deep.value)
        }
        if (sleepAmt.value > 0.01f) drawSleepZs(m, sleepAmt.value, zColor)
    }
}

/** Geometry and expression for one frame. Lengths are in pixels except where noted. */
private class Motion(
    val t: Float, val r: Float, val cx: Float, val cy: Float, val sx: Float, val sy: Float, val tilt: Float,
    val lookX: Float, val lookY: Float, val eyeOpen: Float, val mouthOpen: Float, val floorY: Float, val shadow: Float,
)

private fun DrawScope.motion(state: MascotState, sleeping: Boolean, sleepAmt: Float, a: AnimPose, mic: Float, t: Float, watch: Float): Motion {
    val tau = 2f * PI.toFloat()
    val r = size.minDimension * 0.28f
    val baseX = size.width / 2f
    val baseY = size.height * 0.55f
    var sx = a.sx.value
    var sy = a.sy.value
    var tilt = a.tilt.value
    var lookX = a.lookX.value + watch * 0.7f
    var lookY = a.lookY.value
    var eyeOpen = a.eyeOpen.value
    var mouthOpen = a.mouthOpen.value
    var hop = 0f
    var dx = 0f

    val breathPeriod = 3.2f + 1.4f * sleepAmt
    val b = sin(t * tau / breathPeriod)
    val breath = 0.015f + 0.025f * sleepAmt
    sy *= 1f + breath * b
    sx *= 1f - breath * 0.7f * b

    // A hop: stretch in the air, squash on landing. phase runs 0..1 over one hop.
    fun bounce(phase: Float, height: Float, squash: Float) {
        val h = sin(phase * PI.toFloat())
        hop += height * h
        sy *= 1f + 0.1f * squash * h
        sx *= 1f - 0.06f * squash * h
        val land = (1f - min(phase, 1f - phase) / 0.1f).coerceIn(0f, 1f)
        sy *= 1f - 0.14f * squash * land
        sx *= 1f + 0.12f * squash * land
    }

    when (state) {
        MascotState.IDLE -> if (!sleeping) {
            lookX += 0.55f * sin(t * 0.41f) * sin(t * 0.17f + 1.3f)
            lookY += 0.22f * sin(t * 0.29f + 0.7f)
        }
        MascotState.LISTENING -> {
            sy *= 1f + 0.12f * mic
            sx *= 1f - 0.07f * mic
            tilt += 0.03f * sin(t * 1.3f)
        }
        MascotState.THINKING -> {
            tilt += 0.07f * sin(t * 1.1f)
            lookX += 0.15f * sin(t * 0.9f)
        }
        MascotState.SPEAKING -> {
            mouthOpen = (mouthOpen + 0.42f * abs(sin(t * 8.5f)) * (0.55f + 0.45f * sin(t * 2.7f))).coerceIn(0f, 1f)
            sy *= 1f + 0.018f * sin(t * 17f)
        }
        MascotState.EXECUTING -> bounce((t * 2.2f) % 1f, 0.07f, 0.5f)
        MascotState.ERROR -> dx = sin(t * 36f) * 0.045f * (0.6f + 0.4f * sin(t * 1.7f))
        MascotState.ATTENTION -> {
            val ph = (t * 1.1f) % 1f
            if (ph < 0.45f) bounce(ph / 0.45f, 0.14f, 0.8f)
        }
        MascotState.HAPPY -> bounce((t * 1.3f) % 1f, 0.3f, 1f)
        MascotState.CONFUSED -> tilt += 0.05f * sin(t * 1.6f)
    }

    if (sleepAmt < 0.5f && state != MascotState.HAPPY) {
        val period = 3.9f
        val cycle = floor(t / period).toInt()
        val bt = t - cycle * period
        var blink = if (bt < 0.18f) sin(bt / 0.18f * PI.toFloat()) else 0f
        if (cycle % 3 == 2 && bt in 0.26f..0.44f) blink = max(blink, sin((bt - 0.26f) / 0.18f * PI.toFloat()))
        eyeOpen *= 1f - 0.92f * blink
    }

    return Motion(
        t = t, r = r, cx = baseX + dx * r, cy = baseY + r * (1f - sy) - hop * r, sx = sx, sy = sy, tilt = tilt,
        lookX = lookX, lookY = lookY, eyeOpen = eyeOpen, mouthOpen = mouthOpen,
        floorY = baseY + r * 1.04f, shadow = (1f - hop * 1.4f).coerceIn(0.35f, 1f),
    )
}

private fun RuntimeShader.setUniforms(m: Motion, a: AnimPose, tint: Color) {
    val deep = a.deep.value
    setFloatUniform("uCenter", m.cx, m.cy)
    setFloatUniform("uRadius", m.r)
    setFloatUniform("uScale", m.sx, m.sy)
    setFloatUniform("uTilt", m.tilt)
    setFloatUniform("uTime", m.t)
    setFloatUniform("uWobble", a.wobble.value)
    setFloatUniform("uWobbleSpeed", a.wobbleSpeed.value)
    setFloatUniform("uTip", a.tip.value)
    setFloatUniform("uLook", m.lookX, m.lookY)
    setFloatUniform("uEyeOpen", m.eyeOpen)
    setFloatUniform("uEyeCurve", a.eyeCurve.value)
    setFloatUniform("uEyeAsym", a.eyeAsym.value)
    setFloatUniform("uMouthOpen", m.mouthOpen)
    setFloatUniform("uMouthCurve", a.mouthCurve.value)
    setFloatUniform("uBlush", a.blush.value)
    setFloatUniform("uGlow", a.glow.value)
    setFloatUniform("uBubbles", a.bubbles.value)
    setFloatUniform("uFloor", m.floorY, m.shadow)
    setFloatUniform("uTint", tint.red, tint.green, tint.blue)
    setFloatUniform("uDeep", deep.red, deep.green, deep.blue)
}

/** Floating "Z"s while asleep. */
private fun DrawScope.drawSleepZs(m: Motion, amount: Float, color: Color) {
    for (i in 0 until 3) {
        val ph = (m.t * 0.33f + i / 3f) % 1f
        val x = m.cx + m.r * (0.6f + 0.5f * ph)
        val y = m.cy - m.r * (0.9f + 1.1f * ph)
        val s = m.r * (0.14f + 0.12f * ph)
        val path = Path().apply {
            moveTo(x - s / 2f, y - s / 2f)
            lineTo(x + s / 2f, y - s / 2f)
            lineTo(x - s / 2f, y + s / 2f)
            lineTo(x + s / 2f, y + s / 2f)
        }
        drawPath(
            path, color.copy(alpha = sin(ph * PI.toFloat()) * amount * 0.8f),
            style = Stroke(s * 0.22f, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** Used only if the shader cannot run (software canvas or a compile failure): a shaded ball with eyes. */
private fun DrawScope.drawFallback(m: Motion, tint: Color, deep: Color) {
    val c = Offset(m.cx, m.cy)
    drawCircle(Brush.radialGradient(listOf(tint, deep), Offset(m.cx - m.r * 0.3f, m.cy - m.r * 0.35f), m.r * 1.6f), m.r, c)
    val eyeH = m.r * 0.34f * m.eyeOpen.coerceAtLeast(0.08f)
    for (side in listOf(-1f, 1f)) {
        drawOval(
            Color(0xFF08102A),
            topLeft = Offset(m.cx + side * m.r * 0.3f - m.r * 0.1f, m.cy - m.r * 0.07f - eyeH / 2f),
            size = Size(m.r * 0.2f, eyeH),
        )
    }
    drawOval(Color.White.copy(alpha = 0.6f), topLeft = Offset(m.cx - m.r * 0.55f, m.cy - m.r * 0.75f), size = Size(m.r * 0.38f, m.r * 0.2f))
}
