package com.nyro.companion.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.nyro.companion.NyroApp
import com.nyro.companion.overlay.UiFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The cross-app interaction engine. Bound by the system once the user enables
 * "NYRO Phone Control" in Accessibility settings. It never acts on its own: every
 * gesture is requested by the agent's tool executor after local validation.
 */
class NyroAccessibilityService : AccessibilityService() {

    @Volatile var lastEventAt: Long = 0L; private set
    @Volatile var foregroundPackage: String = ""; private set

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        NyroApp.graph.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        lastEventAt = SystemClock.uptimeMillis()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != packageName) foregroundPackage = pkg
        }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        if (_instance.value === this) _instance.value = null
        super.onDestroy()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (_instance.value === this) _instance.value = null
        return super.onUnbind(intent)
    }

    /** Waits until no accessibility events arrived for [quietMs], bounded by [timeoutMs]. */
    suspend fun waitForIdle(quietMs: Long = 350, timeoutMs: Long = 3000) {
        val start = SystemClock.uptimeMillis()
        delay(120)
        while (SystemClock.uptimeMillis() - start < timeoutMs) {
            if (SystemClock.uptimeMillis() - lastEventAt >= quietMs) return
            delay(60)
        }
    }

    // ---------------- Gestures ----------------

    suspend fun tap(x: Float, y: Float): Boolean {
        UiFeedback.showTap(x, y)
        return gesture(Path().apply { moveTo(x, y) }, 0, 60)
    }

    suspend fun longPress(x: Float, y: Float, durationMs: Long = 700): Boolean {
        UiFeedback.showTap(x, y, long = true)
        return gesture(Path().apply { moveTo(x, y) }, 0, durationMs)
    }

    suspend fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long = 350): Boolean {
        UiFeedback.showSwipe(x1, y1, x2, y2)
        return gesture(Path().apply { moveTo(x1, y1); lineTo(x2, y2) }, 0, durationMs.coerceIn(80, 3000))
    }

    private suspend fun gesture(path: Path, start: Long, duration: Long): Boolean {
        UiFeedback.setTouchPassThrough(true)
        try {
            val stroke = GestureDescription.StrokeDescription(path, start, duration)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            return withTimeoutOrNull(duration + 4000) {
                suspendCancellableCoroutine { cont ->
                    val ok = dispatchGesture(g, object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                        override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                    }, null)
                    if (!ok && cont.isActive) cont.resume(false)
                }
            } ?: false
        } finally {
            delay(40)
            UiFeedback.setTouchPassThrough(false)
        }
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // ---------------- Screenshot ----------------

    sealed class Shot {
        data class Ok(val bitmap: Bitmap) : Shot()
        data class Failed(val reason: String) : Shot()
    }

    /** API 30+ accessibility screenshot. Respects FLAG_SECURE: secure windows are refused by the system. */
    suspend fun screenshot(): Shot {
        repeat(3) { attempt ->
            val r = withTimeoutOrNull(5000) {
                suspendCancellableCoroutine<Shot> { cont ->
                    takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val hw = result.hardwareBuffer
                            val wrapped = Bitmap.wrapHardwareBuffer(hw, result.colorSpace)
                            val soft = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                            wrapped?.recycle(); hw.close()
                            if (cont.isActive) cont.resume(if (soft != null) Shot.Ok(soft) else Shot.Failed("Could not decode screenshot"))
                        }
                        override fun onFailure(errorCode: Int) {
                            val reason = when (errorCode) {
                                ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "RATE"
                                ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "The current screen is protected (secure window); Android does not allow capturing it."
                                ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "Accessibility screenshot permission missing."
                                else -> "Screenshot failed (code $errorCode)."
                            }
                            if (cont.isActive) cont.resume(Shot.Failed(reason))
                        }
                    })
                }
            } ?: Shot.Failed("Screenshot timed out.")
            if (r is Shot.Failed && r.reason == "RATE" && attempt < 2) { delay(1100); return@repeat }
            return if (r is Shot.Failed && r.reason == "RATE") Shot.Failed("Screenshots requested too quickly.") else r
        }
        return Shot.Failed("Screenshot failed.")
    }

    companion object {
        private val _instance = MutableStateFlow<NyroAccessibilityService?>(null)
        val instance: StateFlow<NyroAccessibilityService?> = _instance
        val current: NyroAccessibilityService? get() = _instance.value
    }
}
