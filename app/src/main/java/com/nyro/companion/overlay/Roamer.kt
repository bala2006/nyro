package com.nyro.companion.overlay

import android.graphics.Rect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.mutableFloatStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** Eye direction for the companion while it overlays other apps: -1 looks left, 0 straight ahead, 1 looks right. */
object OverlayGaze {
    val x: MutableFloatState = mutableFloatStateOf(0f)
}

/** A rectangle in window pixels. */
data class Area(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2

    fun overlaps(other: Area): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        fun of(r: Rect): Area = Area(r.left, r.top, r.right, r.bottom)
        fun point(x: Float, y: Float): Area = Area(x.toInt(), y.toInt(), x.toInt() + 1, y.toInt() + 1)
    }
}

/** Where the companion may go and where it stands next to a target. Pure geometry, so it can be tested without a window. */
object RoamPlanner {
    /** Keep this much of the bottom free: the status pill and the gesture bar live there. */
    const val BOTTOM_BAND_DP = 104f

    /** Placement area: inside the screen, below the status bar, above the bottom band. Keyboard open: top half only. */
    fun safeArea(screenW: Int, screenH: Int, insetTop: Int, insetBottom: Int, density: Float, keyboardOpen: Boolean): Area {
        val margin = (8 * density).toInt()
        val bottom = if (keyboardOpen) screenH / 2 else screenH - insetBottom - (BOTTOM_BAND_DP * density).toInt()
        return Area(margin, insetTop + margin, screenW - margin, max(bottom, insetTop + margin + 1))
    }

    /** The resting spot: bottom right of the area. */
    fun restSpot(area: Area, w: Int, h: Int): Pair<Int, Int> = clampIn(area, area.right - w, area.bottom - h, w, h)

    /**
     * A spot beside [focus] that does not cover it: above, then below, then either side. If none fits, the one covering the
     * least of [focus] wins.
     */
    fun beside(area: Area, w: Int, h: Int, focus: Area, gap: Int): Pair<Int, Int> {
        val candidates = listOf(
            clampIn(area, focus.centerX - w / 2, focus.top - gap - h, w, h),
            clampIn(area, focus.centerX - w / 2, focus.bottom + gap, w, h),
            clampIn(area, focus.right + gap, focus.centerY - h / 2, w, h),
            clampIn(area, focus.left - gap - w, focus.centerY - h / 2, w, h),
        )
        return candidates.minByOrNull { (x, y) -> overlap(Area(x, y, x + w, y + h), focus) } ?: restSpot(area, w, h)
    }

    fun clampIn(area: Area, x: Int, y: Int, w: Int, h: Int): Pair<Int, Int> =
        clamp(x, area.left, area.right - w) to clamp(y, area.top, area.bottom - h)

    private fun clamp(v: Int, lo: Int, hi: Int): Int = if (hi < lo) lo else min(max(v, lo), hi)

    private fun overlap(a: Area, b: Area): Int {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        return if (w <= 0 || h <= 0) 0 else w * h
    }
}

/**
 * Moves the floating companion according to what NYRO is doing. Nothing moves at random; every destination is a target:
 *  - typing: it moves beside the field being typed into, looks at it, and stays while the typing lasts
 *  - reading or tapping an element: it glides beside that element and looks at it
 *  - scanning the screen: it peeks at a real clickable element from the latest snapshot
 *  - working with nothing to point at for a few seconds: it returns to its resting corner
 *  - listening, thinking, speaking, or idle: it rests in the corner; while idle it peeks at an element now and then
 * A drag always wins: nothing moves until the drag ends and a short pause has passed.
 */
class Roamer(
    private val scope: CoroutineScope,
    private val host: Host,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** What the roamer needs from the window it moves. */
    interface Host {
        /** Where the companion may be placed right now. */
        fun area(): Area

        /** Companion window size in px, or (0, 0) while it is not shown. */
        fun window(): Pair<Int, Int>

        /** Current top-left corner in px. */
        fun position(): Pair<Int, Int>

        /** Moves the window. The point is already inside [area]. */
        fun place(x: Int, y: Int)

        fun density(): Float

        /** The roaming setting is on and the companion is visible. */
        fun roamingAllowed(): Boolean

        /** Clickable, on-screen elements from the latest screen snapshot. */
        fun elements(): List<Area>

        /** Sets the eye direction, -1..1. */
        fun gaze(dir: Float)
    }

    private val rnd = Random(clock())
    private var loop: Job? = null
    private var glide: Job? = null
    private var working = false
    private var pausedUntil = 0L
    private var lastActivityAt = 0L
    private var typingUntil = 0L
    private var nextPeekAt = 0L

    fun start() {
        if (loop != null) return
        loop = scope.launch {
            while (isActive) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    fun stop() {
        loop?.cancel()
        glide?.cancel()
        loop = null
    }

    /** True while NYRO is executing actions on the phone. */
    fun setWorking(on: Boolean) {
        working = on
    }

    /** The user started dragging the companion: stop moving and stay put until [onUserMoveEnd]. */
    fun onUserMoveStart() {
        glide?.cancel()
        pausedUntil = Long.MAX_VALUE
    }

    fun onUserMoveEnd() {
        pausedUntil = clock() + RESUME_AFTER_DRAG_MS
    }

    /** NYRO reads or taps an element: glide beside it and look at it. */
    fun lookAt(focus: Area) {
        activity()
        pointAt(focus)
    }

    /** NYRO taps a point: glance at it from a nearby spot. */
    fun glance(x: Float, y: Float) = lookAt(Area.point(x, y))

    /** NYRO is typing into [field]: go beside it, look at it, and stay while the typing lasts. */
    fun typing(field: Area) {
        typingUntil = clock() + TYPING_HOLD_MS
        lookAt(field)
    }

    /** NYRO reads the screen: peek at one of its real elements. */
    fun onScan() {
        activity()
        peek()
    }

    private fun activity() {
        lastActivityAt = clock()
    }

    private fun canMove(): Boolean = host.roamingAllowed() && clock() >= pausedUntil

    private fun pointAt(focus: Area) {
        if (!canMove()) return
        val (w, h) = host.window()
        if (w == 0) return
        val (x, y) = RoamPlanner.beside(host.area(), w, h, focus, gap = (10 * host.density()).toInt())
        val centerX = x + w / 2
        host.gaze(
            when {
                focus.centerX - centerX > w / 4 -> 1f
                focus.centerX - centerX < -w / 4 -> -1f
                else -> 0f
            },
        )
        animateTo(x to y)
    }

    private fun peek() {
        if (!canMove()) return
        val targets = host.elements().filter { it.width >= MIN_PEEK_PX && it.height >= MIN_PEEK_PX }
        if (targets.isEmpty()) return
        pointAt(targets[rnd.nextInt(targets.size)])
    }

    private fun tick() {
        if (!canMove()) return
        val (w, h) = host.window()
        if (w == 0 || glide?.isActive == true || clock() < typingUntil) return
        val idleFor = clock() - lastActivityAt
        if (idleFor > GAZE_RESET_MS) host.gaze(0f)
        when {
            working && idleFor > WORK_REST_AFTER_MS -> rest(w, h)
            !working && idleFor > SLEEP_AFTER_MS -> rest(w, h)
            !working && clock() >= nextPeekAt && idleFor > PEEK_QUIET_MS -> {
                nextPeekAt = clock() + PEEK_MIN_MS + rnd.nextLong(PEEK_JITTER_MS)
                peek()
            }
        }
    }

    private fun rest(w: Int, h: Int) {
        val (rx, ry) = RoamPlanner.restSpot(host.area(), w, h)
        val (cx, cy) = host.position()
        if (abs(rx - cx) + abs(ry - cy) < REST_TOLERANCE_PX) return
        host.gaze(0f)
        animateTo(rx to ry)
    }

    private fun animateTo(target: Pair<Int, Int>, durationMs: Long = GLIDE_MS) {
        glide?.cancel()
        glide = scope.launch {
            val (sx, sy) = host.position()
            val (tx, ty) = target
            val steps = max((durationMs / FRAME_MS).toInt(), 2)
            for (i in 1..steps) {
                if (!canMove()) return@launch
                val t = i.toFloat() / steps
                val e = if (t < 0.5f) 4 * t * t * t else 1 - (-2 * t + 2).let { it * it * it } / 2
                host.place(sx + ((tx - sx) * e).toInt(), sy + ((ty - sy) * e).toInt())
                delay(FRAME_MS)
            }
        }
    }

    companion object {
        private const val TICK_MS = 500L
        private const val GLIDE_MS = 700L
        private const val FRAME_MS = 33L
        private const val TYPING_HOLD_MS = 2_500L
        private const val GAZE_RESET_MS = 3_000L
        private const val WORK_REST_AFTER_MS = 3_000L
        private const val SLEEP_AFTER_MS = 20_000L
        private const val PEEK_QUIET_MS = 10_000L
        private const val PEEK_MIN_MS = 30_000L
        private const val PEEK_JITTER_MS = 30_000L
        private const val REST_TOLERANCE_PX = 16
        private const val MIN_PEEK_PX = 40
        private const val RESUME_AFTER_DRAG_MS = 6_000L
    }
}

