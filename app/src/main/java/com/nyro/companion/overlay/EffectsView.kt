package com.nyro.companion.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.abs
import kotlin.math.sin
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.compose.ui.graphics.toArgb
import com.nyro.companion.NyroApp
import com.nyro.companion.R
import com.nyro.companion.ui.theme.Nyro

/**
 * Full-screen, non-touchable layer that shows what NYRO is doing on screen:
 *  - a focus ring that glides to the element being acted on
 *  - tap ripples, long-press rings, swipe trails and a flash on highlighted targets
 *  - a pulsing typing indicator on the field NYRO is typing into
 *  - a scan sweep while NYRO reads the screen
 *  - an animated border while a tool runs
 *  - the status pill at the bottom, above the gesture bar, visible only while NYRO is busy and for 5 s after
 * Frames are scheduled only while something is animating, so the idle overlay costs nothing.
 */
class EffectsView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    // Palette follows the user's dark/light setting; Nyro.use must run before the colors are read.
    private val isDark = NyroApp.graph.settings.current.darkTheme.also { Nyro.use(it) }
    private val cyan = Nyro.Cyan.toArgb()
    private val blue = Nyro.Blue.toArgb()
    private val violet = Nyro.Steel.toArgb()

    // ---- one-shot effects ----
    private enum class Kind { RIPPLE, LONG_RIPPLE, SWIPE, FLASH }
    private class Fx(val kind: Kind, val a: RectF, val start: Long, val dur: Long)
    private val effects = ArrayList<Fx>()

    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = cyan }
    private val flash = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan; strokeWidth = 2.5f * density }

    // ---- focus ring: one ring that glides between targets ----
    private val focusNow = RectF()
    private val focusTo = RectF()
    private var focusSetAt = 0L
    private var focusActive = false
    private val focusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan; strokeWidth = 2.5f * density }
    private val focusGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan; strokeWidth = 9f * density }

    // ---- typing indicator on the active input ----
    private val typeRect = RectF()
    private var typeUntil = 0L
    private var typeAlpha = 0f
    private val typePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = violet; strokeWidth = 2.5f * density }
    private val typeDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = violet }

    // ---- scan sweep while reading the screen ----
    private var scanStart = 0L
    private var scanning = false
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scanBand = 140 * density
    private val scanShader by lazy {
        LinearGradient(0f, 0f, 0f, scanBand, intArrayOf(Color.TRANSPARENT, ColorUtils.setAlphaComponent(cyan, 95), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
    }

    // ---- executing border ----
    private var executing = false
    private var borderPhase = 0f
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val borderGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f * density; alpha = 70 }
    private var gradient: LinearGradient? = null
    private val gradMatrix = Matrix()

    // ---- status pill ----
    private var status: String? = null
    private var metrics: String? = null
    private var pillAlpha = 0f
    private var pillTarget = 0f
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ColorUtils.setAlphaComponent(Nyro.Glass.toArgb(), 236) }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = ColorUtils.setAlphaComponent(cyan, 150) }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Nyro.TextHi.toArgb(); textSize = 13f * density; typeface = ResourcesCompat.getFont(context, R.font.inter_medium) }
    private val pillSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Nyro.Teal.toArgb(); textSize = 11f * density; typeface = ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) }
    private val pillDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    private val hideHandler = Handler(Looper.getMainLooper())
    private val hideLater = Runnable { pillTarget = 0f; ensureRunning() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(blue, cyan, ColorUtils.setAlphaComponent(cyan, 30), blue), null, Shader.TileMode.MIRROR)
        border.shader = gradient; borderGlow.shader = gradient
    }

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { borderPhase = it.animatedValue as Float; step() }
    }

    // ---------------- Public API ----------------

    fun tap(x: Float, y: Float, long: Boolean) = addFx(Fx(if (long) Kind.LONG_RIPPLE else Kind.RIPPLE, RectF(x, y, x, y), now(), if (long) 900 else 550))
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = addFx(Fx(Kind.SWIPE, RectF(x1, y1, x2, y2), now(), 700))
    fun highlight(r: android.graphics.Rect) = addFx(Fx(Kind.FLASH, RectF(r), now(), 650))

    /** Moves the focus ring to [r]. It glides from the previous target and fades after about 2.5 s without a new focus. */
    fun focus(r: android.graphics.Rect) {
        focusTo.set(r)
        if (!focusActive) focusNow.set(r)
        focusActive = true
        focusSetAt = now()
        ensureRunning()
    }

    /** Pulsing typing indicator on [r]. Each call extends it by about 1.8 s. */
    fun typing(r: android.graphics.Rect) {
        typeRect.set(r)
        typeUntil = now() + 1800
        ensureRunning()
    }

    fun scan() {
        scanStart = now(); scanning = true; ensureRunning()
    }

    fun setExecuting(on: Boolean) {
        executing = on
        if (on) ensureRunning() else invalidate()
    }

    fun setStatus(text: String?, sub: String? = null, dotColor: Int = cyan) {
        val t = text?.let { if (it.length > 44) it.take(42) + "…" else it }
        if (t == status && sub == metrics && pillDot.color == dotColor) return
        status = t; metrics = sub; pillDot.color = dotColor
        invalidate()
    }

    /** Shows the pill while NYRO is busy. When work stops, it hides 5 s later. */
    fun setBusy(on: Boolean) {
        hideHandler.removeCallbacks(hideLater)
        if (on) { pillTarget = 1f; ensureRunning() } else hideHandler.postDelayed(hideLater, PILL_HIDE_DELAY_MS)
    }

    // ---------------- Animation ----------------

    private fun now() = SystemClock.uptimeMillis()

    private fun addFx(f: Fx) { effects += f; ensureRunning() }

    private fun ensureRunning() { if (!ticker.isRunning) ticker.start() }

    /** Advances all animated values by one frame and stops the ticker when nothing is moving. */
    private fun step() {
        val t = now()
        effects.removeAll { t - it.start > it.dur }
        if (focusActive) {
            focusNow.left += (focusTo.left - focusNow.left) * 0.22f
            focusNow.top += (focusTo.top - focusNow.top) * 0.22f
            focusNow.right += (focusTo.right - focusNow.right) * 0.22f
            focusNow.bottom += (focusTo.bottom - focusNow.bottom) * 0.22f
            if (t - focusSetAt > FOCUS_HOLD_MS + FOCUS_FADE_MS) focusActive = false
        }
        val typeTarget = if (t < typeUntil) 1f else 0f
        typeAlpha = settle(typeAlpha, typeTarget, 0.2f)
        pillAlpha = settle(pillAlpha, pillTarget, 0.15f)
        if (scanning && t - scanStart > SCAN_MS) scanning = false

        invalidate()
        val moving = effects.isNotEmpty() || executing || focusActive || scanning || typeAlpha > 0f || abs(pillAlpha - pillTarget) > 0.005f
        if (!moving) ticker.cancel()
    }

    private fun settle(v: Float, target: Float, k: Float): Float {
        val n = v + (target - v) * k
        return if (abs(n - target) < 0.01f) target else n
    }

    override fun onDetachedFromWindow() {
        ticker.cancel()
        hideHandler.removeCallbacks(hideLater)
        super.onDetachedFromWindow()
    }

    // ---------------- Drawing ----------------

    override fun onDraw(canvas: Canvas) {
        val t = now()
        drawScan(canvas, t)
        drawFocus(canvas, t)
        drawTyping(canvas, t)
        for (f in effects) drawFx(canvas, f, t)
        drawBorder(canvas)
        drawPill(canvas)
    }

    private fun drawScan(canvas: Canvas, t: Long) {
        if (!scanning) return
        val p = ((t - scanStart).toFloat() / SCAN_MS).coerceIn(0f, 1f)
        val y = -scanBand + (height + scanBand) * p
        scanPaint.shader = scanShader
        canvas.save()
        canvas.translate(0f, y)
        canvas.drawRect(0f, 0f, width.toFloat(), scanBand, scanPaint)
        canvas.restore()
    }

    private fun drawFocus(canvas: Canvas, t: Long) {
        if (!focusActive) return
        val age = t - focusSetAt
        val alpha = when {
            age < FOCUS_HOLD_MS -> 1f
            else -> (1f - (age - FOCUS_HOLD_MS).toFloat() / FOCUS_FADE_MS).coerceIn(0f, 1f)
        }
        if (alpha <= 0f) return
        val r = RectF(focusNow).apply { inset(-5 * density, -5 * density) }
        focusGlow.alpha = (alpha * 70).toInt()
        focusPaint.alpha = (alpha * 235).toInt()
        canvas.drawRoundRect(r, 14 * density, 14 * density, focusGlow)
        canvas.drawRoundRect(r, 14 * density, 14 * density, focusPaint)
    }

    private fun drawTyping(canvas: Canvas, t: Long) {
        if (typeAlpha <= 0f) return
        val a = typeAlpha
        val pulse = (sin(t / 220.0).toFloat() + 1f) / 2f
        val r = RectF(typeRect).apply { inset(-3 * density, -3 * density) }
        typePaint.alpha = (a * (120 + 120 * pulse)).toInt()
        typePaint.strokeWidth = (2f + 1.5f * pulse) * density
        canvas.drawRoundRect(r, 12 * density, 12 * density, typePaint)
        // three dots, staggered, at the right edge of the field
        for (i in 0 until 3) {
            val phase = t / 180.0 + i * 0.9
            val lift = (sin(phase).toFloat() + 1f) / 2f
            typeDot.alpha = (a * (90 + 165 * lift)).toInt()
            val cx = typeRect.right - (16 + i * 11) * density
            val cy = typeRect.centerY() - lift * 3f * density
            canvas.drawCircle(cx, cy, 3f * density, typeDot)
        }
    }

    private fun drawFx(canvas: Canvas, f: Fx, t: Long) {
        val p = ((t - f.start).toFloat() / f.dur).coerceIn(0f, 1f)
        val alpha = ((1f - p) * 255).toInt()
        when (f.kind) {
            Kind.RIPPLE, Kind.LONG_RIPPLE -> {
                val maxR = (if (f.kind == Kind.LONG_RIPPLE) 46f else 34f) * density
                ripple.strokeWidth = 3f * density * (1f - p) + 1f
                ripple.alpha = alpha
                canvas.drawCircle(f.a.left, f.a.top, maxR * (0.25f + 0.75f * p), ripple)
                dot.alpha = (alpha * 0.8f).toInt()
                canvas.drawCircle(f.a.left, f.a.top, 7f * density * (1f - p * 0.5f), dot)
            }
            Kind.SWIPE -> {
                trail.strokeWidth = 8f * density * (1f - p) + 2f
                trail.alpha = alpha
                val k = (p * 1.6f).coerceAtMost(1f)
                canvas.drawLine(f.a.left, f.a.top, f.a.left + (f.a.right - f.a.left) * k, f.a.top + (f.a.bottom - f.a.top) * k, trail)
            }
            Kind.FLASH -> {
                flash.alpha = alpha
                val r = RectF(f.a).apply { inset(-4f * density * p, -4f * density * p) }
                canvas.drawRoundRect(r, 10f * density, 10f * density, flash)
            }
        }
    }

    private fun drawBorder(canvas: Canvas) {
        if (!executing) return
        val w = width.toFloat(); val h = height.toFloat()
        gradMatrix.setTranslate(borderPhase * w * 2, borderPhase * h * 2)
        gradient?.setLocalMatrix(gradMatrix)
        val inset = 2f * density
        val rr = RectF(inset, inset, w - inset, h - inset)
        canvas.drawRoundRect(rr, 28f * density, 28f * density, borderGlow)
        canvas.drawRoundRect(rr, 28f * density, 28f * density, border)
    }

    private fun drawPill(canvas: Canvas) {
        if (pillAlpha <= 0.01f) return
        val txt = status ?: return
        // Keep the pill inside the screen: truncate text instead of letting it run off an edge.
        val maxTextW = width - 2 * 24f * density - 2 * 14f * density - 12f * density
        val label = fit("NYRO · $txt", pillText, maxTextW)
        val sub = metrics?.let { fit(it, pillSub, maxTextW) }
        val tw = maxOf(pillText.measureText(label), sub?.let { pillSub.measureText(it) } ?: 0f)
        val h = (if (sub != null) 46f else 32f) * density
        val padX = 14f * density
        // The pill sits above the gesture bar, at the bottom of the screen.
        val navBottom = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.navigationBars())?.bottom?.toFloat() ?: 0f
        val top = height - navBottom - 44f * density - h
        val w = tw + padX * 2 + 12f * density
        val left = (width - w) / 2f
        val rr = RectF(left, top, left + w, top + h)
        canvas.saveLayerAlpha(rr.left - 8, rr.top - 8, rr.right + 8, rr.bottom + 8, (pillAlpha * 255).toInt())
        canvas.drawRoundRect(rr, 16f * density, 16f * density, pillBg)
        canvas.drawRoundRect(rr, 16f * density, 16f * density, pillEdge)
        val textX = rr.left + padX + 12f * density
        val line1Y = if (sub != null) rr.top + 19f * density else rr.centerY() - (pillText.descent() + pillText.ascent()) / 2
        canvas.drawCircle(rr.left + padX + 2f * density, line1Y + (pillText.ascent() + pillText.descent()) / 2, 3.5f * density, pillDot)
        canvas.drawText(label, textX, line1Y, pillText)
        if (sub != null) canvas.drawText(sub, textX, rr.top + 37f * density, pillSub)
        canvas.restore()
    }

    private fun fit(s: String, p: Paint, max: Float): String {
        if (p.measureText(s) <= max) return s
        var t = s
        while (t.length > 1 && p.measureText(t + "…") > max) t = t.dropLast(1)
        return t + "…"
    }

    companion object {
        private const val PILL_HIDE_DELAY_MS = 5000L
        private const val FOCUS_HOLD_MS = 2500L
        private const val FOCUS_FADE_MS = 500L
        private const val SCAN_MS = 900L
    }
}
