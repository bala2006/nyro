package com.nyro.companion.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * Full-screen, non-touchable layer that makes NYRO's actions visible: tap ripples, swipe trails,
 * highlighted target elements and a soft animated border while NYRO is in control.
 * It only animates while something is on screen; otherwise it draws nothing.
 */
class EffectsView(context: Context) : View(context) {
    private data class Fx(val kind: Int, val a: RectF, val start: Long, val dur: Long)

    private val effects = ArrayList<Fx>()
    private var executing = false
    private var borderPhase = 0f
    private val density = resources.displayMetrics.density

    private val cyan = Color.rgb(56, 232, 255)
    private val blue = Color.rgb(47, 123, 255)
    private val ripple = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }
    private val trail = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = cyan }
    private val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = cyan; strokeWidth = 2.5f * density }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f * density }
    private val borderGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 10f * density; alpha = 70 }
    private var gradient: LinearGradient? = null
    private val gradMatrix = android.graphics.Matrix()

    private val ticker = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { borderPhase = it.animatedValue as Float; invalidate(); stopIfIdle() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        gradient = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(blue, cyan, Color.argb(30, 56, 232, 255), blue), null, Shader.TileMode.MIRROR)
        border.shader = gradient; borderGlow.shader = gradient
    }

    fun tap(x: Float, y: Float, long: Boolean) = add(Fx(if (long) 1 else 0, RectF(x, y, x, y), now(), if (long) 900 else 550))
    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float) = add(Fx(2, RectF(x1, y1, x2, y2), now(), 700))
    fun highlight(r: android.graphics.Rect) = add(Fx(3, RectF(r), now(), 650))

    private var status: String? = null
    private var metrics: String? = null
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 8, 16, 38) }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.2f * density; color = Color.argb(150, 56, 232, 255) }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(234, 242, 255); textSize = 13f * density; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL) }
    private val pillSub = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(140, 214, 235); textSize = 11f * density; typeface = android.graphics.Typeface.MONOSPACE }
    private val pillDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = cyan }

    /** Top-of-screen pill: what NYRO is doing right now, plus token usage and speed. */
    fun setStatus(text: String?, sub: String? = null, dotColor: Int = cyan) {
        val t = text?.let { if (it.length > 44) it.take(42) + "…" else it }
        if (t == status && sub == metrics && pillDot.color == dotColor) return
        status = t; metrics = sub; pillDot.color = dotColor
        invalidate()
    }

    fun setExecuting(on: Boolean) {
        executing = on
        if (on) ensureRunning() else invalidate()
    }

    private fun add(f: Fx) { effects += f; ensureRunning() }
    private fun ensureRunning() { if (!ticker.isRunning) ticker.start() }
    private fun stopIfIdle() {
        val t = now()
        effects.removeAll { t - it.start > it.dur }
        if (effects.isEmpty() && !executing) { ticker.cancel(); invalidate() }
    }
    private fun now() = SystemClock.uptimeMillis()

    override fun onDetachedFromWindow() { ticker.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val t = now()
        if (executing) {
            val w = width.toFloat(); val h = height.toFloat()
            gradMatrix.setTranslate(borderPhase * w * 2, borderPhase * h * 2)
            gradient?.setLocalMatrix(gradMatrix)
            val inset = 2f * density
            val rr = RectF(inset, inset, w - inset, h - inset)
            canvas.drawRoundRect(rr, 28f * density, 28f * density, borderGlow)
            canvas.drawRoundRect(rr, 28f * density, 28f * density, border)
        }
        status?.let { txt ->
            val label = "NYRO · $txt"
            val sub = metrics
            val tw = maxOf(pillText.measureText(label), sub?.let { pillSub.measureText(it) } ?: 0f)
            val h = (if (sub != null) 46f else 32f) * density; val padX = 14f * density
            val top = rootWindowInsets?.getInsets(android.view.WindowInsets.Type.statusBars())?.top?.toFloat()?.plus(4f * density) ?: (34f * density)
            val w = tw + padX * 2 + 12f * density
            val left = (width - w) / 2f
            val rr = RectF(left, top, left + w, top + h)
            canvas.drawRoundRect(rr, 16f * density, 16f * density, pillBg)
            canvas.drawRoundRect(rr, 16f * density, 16f * density, pillEdge)
            val textX = rr.left + padX + 12f * density
            val line1Y = if (sub != null) rr.top + 19f * density else rr.centerY() - (pillText.descent() + pillText.ascent()) / 2
            canvas.drawCircle(rr.left + padX + 2f * density, line1Y + (pillText.ascent() + pillText.descent()) / 2, 3.5f * density, pillDot)
            canvas.drawText(label, textX, line1Y, pillText)
            if (sub != null) canvas.drawText(sub, textX, rr.top + 37f * density, pillSub)
        }
        for (f in effects) {
            val p = ((t - f.start).toFloat() / f.dur).coerceIn(0f, 1f)
            val alpha = ((1f - p) * 255).toInt()
            when (f.kind) {
                0, 1 -> {
                    val maxR = (if (f.kind == 1) 46f else 34f) * density
                    ripple.strokeWidth = 3f * density * (1f - p) + 1f
                    ripple.alpha = alpha
                    canvas.drawCircle(f.a.left, f.a.top, maxR * (0.25f + 0.75f * p), ripple)
                    dot.alpha = (alpha * 0.8f).toInt()
                    canvas.drawCircle(f.a.left, f.a.top, 7f * density * (1f - p * 0.5f), dot)
                }
                2 -> {
                    trail.strokeWidth = 8f * density * (1f - p) + 2f
                    trail.alpha = alpha
                    val k = (p * 1.6f).coerceAtMost(1f)
                    canvas.drawLine(f.a.left, f.a.top, f.a.left + (f.a.right - f.a.left) * k, f.a.top + (f.a.bottom - f.a.top) * k, trail)
                }
                3 -> {
                    box.alpha = alpha
                    val r = RectF(f.a); r.inset(-4f * density * p, -4f * density * p)
                    canvas.drawRoundRect(r, 10f * density, 10f * density, box)
                }
            }
        }
    }
}
