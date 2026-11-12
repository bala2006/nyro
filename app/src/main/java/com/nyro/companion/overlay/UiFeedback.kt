package com.nyro.companion.overlay

import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the overlay must do; implemented by [NyroModeService] while NYRO Mode is on. */
interface OverlayHost {
    fun showTap(x: Float, y: Float, long: Boolean)
    fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float)
    fun highlight(r: Rect)
    fun focus(r: Rect)
    fun typing(r: Rect)
    fun scan()
    fun setTouchPassThrough(on: Boolean)
    fun setExecuting(on: Boolean)
    suspend fun collapsePanel(): Boolean
    fun expandPanel()
    suspend fun <T> hidden(block: suspend () -> T): T
}

/**
 * Visible feedback for everything NYRO does on the phone. Calls are safe from any thread and are
 * no-ops while NYRO Mode (and therefore the overlay) is off.
 */
object UiFeedback {
    @Volatile var host: OverlayHost? = null

    private val _status = MutableStateFlow<String?>(null)
    val statusText: StateFlow<String?> = _status
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    fun showTap(x: Float, y: Float, long: Boolean = false) { host?.showTap(x, y, long) }
    fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float) { host?.showSwipe(x1, y1, x2, y2) }
    fun highlight(r: Rect) { host?.highlight(Rect(r)) }
    /** Moves the focus ring to the element being acted on. */
    fun focus(r: Rect) { host?.focus(Rect(r)) }
    /** Shows the typing indicator on the input field. */
    fun typing(r: Rect) { host?.typing(Rect(r)) }
    /** Sweeps a scan line over the screen while NYRO reads it. */
    fun scan() { host?.scan() }
    fun setTouchPassThrough(on: Boolean) { host?.setTouchPassThrough(on) }
    fun setExecuting(on: Boolean) { host?.setExecuting(on); if (!on) _status.value = null }
    fun status(text: String?) { _status.value = text }
    fun notice(text: String?) { _notice.value = text }
    fun requestAttention() { host?.expandPanel() }
    suspend fun collapseForAction() { host?.collapsePanel() }
    suspend fun <T> hiddenForCapture(block: suspend () -> T): T = host?.hidden(block) ?: block()
}
