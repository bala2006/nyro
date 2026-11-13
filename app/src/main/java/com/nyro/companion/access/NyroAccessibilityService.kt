package com.nyro.companion.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.nyro.companion.NyroApp
import com.nyro.companion.exec.EventGate
import com.nyro.companion.exec.EventKind
import com.nyro.companion.overlay.NyroModeService
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf
import com.nyro.companion.perf.RealClock
import com.nyro.companion.ui.MainActivity
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

    @Volatile var foregroundPackage: String = ""; private set

    /**
     * Accessibility events turned into version counters and bounded waits (see [EventGate]). Events from NYRO's own
     * overlay are ignored, so its status pill and focus ring never look like the user's UI changing.
     */
    val gate: EventGate by lazy { EventGate(RealClock, packageName) }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _instance.value = this
        NyroApp.graph.onAccessibilityConnected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        gate.onEvent(
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> EventKind.WINDOW_STATE
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> EventKind.WINDOW_CONTENT
                AccessibilityEvent.TYPE_WINDOWS_CHANGED -> EventKind.WINDOWS
                AccessibilityEvent.TYPE_VIEW_SCROLLED -> EventKind.SCROLL
                AccessibilityEvent.TYPE_VIEW_FOCUSED -> EventKind.FOCUS
                else -> EventKind.OTHER
            },
            event.packageName?.toString(),
        )
        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            NyroModeService.instance?.onKeyboardState(ScreenReader.keyboardOpen(this))
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString().orEmpty()
            if (pkg.isNotEmpty() && pkg != packageName && pkg != foregroundPackage) {
                foregroundPackage = pkg
                NyroModeService.instance?.onForegroundApp(pkg)
            } else if (pkg == packageName && event.className?.toString() == MainActivity::class.java.name && foregroundPackage != pkg) {
                // NYRO's own screen is in front. Its overlay windows also send events from this package, but not as this activity,
                // so they do not take the foreground away from the app underneath.
                foregroundPackage = pkg
            }
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

    /**
     * Kept for older call sites. Returns immediately when the UI is already quiet instead of always paying a fixed delay.
     * Prefer [EventGate.awaitSettled] with the version captured before the action.
     */
    suspend fun waitForIdle(quietMs: Long = 150, timeoutMs: Long = 1500) {
        Perf.time(Metric.UI_WAIT) { gate.awaitQuiet(quietMs, timeoutMs) }
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

    private suspend fun gesture(path: Path, start: Long, duration: Long): Boolean = Perf.time(Metric.GESTURE) {
        UiFeedback.setTouchPassThrough(true)
        try {
            val stroke = GestureDescription.StrokeDescription(path, start, duration)
            val g = GestureDescription.Builder().addStroke(stroke).build()
            withTimeoutOrNull(duration + 4000) {
                suspendCancellableCoroutine { cont ->
                    val ok = dispatchGesture(g, object : GestureResultCallback() {
                        override fun onCompleted(d: GestureDescription?) { if (cont.isActive) cont.resume(true) }
                        override fun onCancelled(d: GestureDescription?) { if (cont.isActive) cont.resume(false) }
                    }, null)
                    if (!ok && cont.isActive) cont.resume(false)
                }
            } ?: false
        } finally {
            UiFeedback.setTouchPassThrough(false)
        }
    }

    fun global(action: Int): Boolean = performGlobalAction(action)

    // ---------------- Screenshot ----------------

    sealed class Shot {
        data class Ok(val bitmap: Bitmap) : Shot()
        data class Failed(val reason: String) : Shot()
    }

    @Volatile private var lastShotAt = 0L

    /**
     * API 30+ accessibility screenshot. Respects FLAG_SECURE: secure windows are refused by the system.
     * Android rate-limits captures. Instead of a fixed 1.1 s sleep after a refusal, retry with short growing waits.
     */
    suspend fun screenshot(): Shot {
        val waits = longArrayOf(250, 500, 900)
        repeat(waits.size + 1) { attempt ->
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
            if (r is Shot.Ok) lastShotAt = SystemClock.elapsedRealtime()
            if (r is Shot.Failed && r.reason == "RATE") {
                if (attempt < waits.size) { delay(waits[attempt]); return@repeat }
                return Shot.Failed("Screenshots requested too quickly.")
            }
            return r
        }
        return Shot.Failed("Screenshot failed.")
    }

    companion object {
        private val _instance = MutableStateFlow<NyroAccessibilityService?>(null)
        val instance: StateFlow<NyroAccessibilityService?> = _instance
        val current: NyroAccessibilityService? get() = _instance.value
    }
}
