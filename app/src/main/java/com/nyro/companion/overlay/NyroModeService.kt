package com.nyro.companion.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.nyro.companion.NyroApp
import com.nyro.companion.R
import com.nyro.companion.access.ScreenCaptureManager
import com.nyro.companion.ui.MainActivity
import com.nyro.companion.ui.theme.NyroTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * NYRO Mode. A foreground service that owns the floating companion and its windows:
 *  - orb window: the draggable entity, status bubble and emergency stop
 *  - confirm window: approval card for risky actions, shown under the status pill
 *  - effects window: full-screen, non-touchable feedback layer
 * Normal Mode = this service is not running: no overlay, no mic, no capture.
 */
class NyroModeService : LifecycleService(), SavedStateRegistryOwner, ViewModelStoreOwner, OverlayHost {
    private val ssrc = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = ssrc.savedStateRegistry
    override val viewModelStore = ViewModelStore()

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())

    private var orbView: ComposeView? = null
    private var confirmView: ComposeView? = null
    private lateinit var confirmParams: WindowManager.LayoutParams
    private var effects: EffectsView? = null
    private lateinit var orbParams: WindowManager.LayoutParams
    private lateinit var effectsParams: WindowManager.LayoutParams

    private var fgsTypes = 0

    override fun onCreate() {
        ssrc.performAttach()
        ssrc.performRestore(null)
        super.onCreate()
        val g = NyroApp.graph
        createChannel()
        // startForegroundService() obliges us to call startForeground() even if we stop right away.
        goForeground(includeProjection = false)
        if (!g.settings.current.nyroMode || !android.provider.Settings.canDrawOverlays(this)) {
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return
        }
        instance = this
        wm = getSystemService(WindowManager::class.java)
        addEffects()
        addOrb()
        addConfirm()
        UiFeedback.host = this
        g.voice.warmUp()
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(
                g.agent.state, g.voice.speaking, UiFeedback.statusText, UiFeedback.notice, g.usage.state,
            ) { st, speaking, tool, notice, u -> pillFor(st, speaking, tool, notice, u) }
                .collect { (text, sub, color) -> effects?.setStatus(text, sub, color) }
        }
        // The top pill is visible while NYRO is busy (task, listening, approval, speaking, notice).
        lifecycleScope.launch {
            kotlinx.coroutines.flow.combine(g.agent.state, g.voice.speaking, UiFeedback.notice) { st, speaking, notice ->
                st.taskActive || st.phase == com.nyro.companion.agent.Phase.LISTENING || st.confirmation != null || speaking || notice != null
            }.distinctUntilChanged().collect { busy -> effects?.setBusy(busy) }
        }
        lifecycleScope.launch {
            UiFeedback.notice.collect { n -> if (n != null) { delay(2600); if (UiFeedback.notice.value == n) UiFeedback.notice(null) } }
        }
        g.mode.onServiceStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_OFF -> lifecycleScope.launch { NyroApp.graph.mode.disable() }
            ACTION_STOP -> NyroApp.graph.agent.stop()
            ACTION_LISTEN -> NyroApp.graph.agent.startListening()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) { instance = null; NyroApp.graph.mode.onServiceStopped() }
        if (UiFeedback.host === this) UiFeedback.host = null
        NyroApp.graph.agent.stop()
        ScreenCaptureManager.release()
        if (::wm.isInitialized) {
            listOfNotNull(orbView, confirmView, effects).forEach { v -> runCatching { wm.removeViewImmediate(v) } }
        }
        orbView = null; confirmView = null; effects = null
        viewModelStore.clear()
        super.onDestroy()
    }

    /** Status pill content. Always shown in NYRO Mode, so the user can see what NYRO is doing. */
    private fun pillFor(
        st: com.nyro.companion.agent.AssistantState, speaking: Boolean, tool: String?, notice: String?,
        u: com.nyro.companion.agent.UsageState,
    ): Triple<String, String, Int> {
        val cyan = android.graphics.Color.rgb(56, 232, 255)
        val (text, color) = when {
            notice != null -> notice to cyan
            st.confirmation != null -> "Needs your OK: ${st.confirmation.text}" to android.graphics.Color.rgb(255, 181, 71)
            st.phase == com.nyro.companion.agent.Phase.ERROR -> "Problem: ${st.error ?: "request failed"}" to android.graphics.Color.rgb(255, 77, 109)
            st.phase == com.nyro.companion.agent.Phase.LISTENING -> (st.partialTranscript.ifBlank { "Listening…" }) to android.graphics.Color.rgb(92, 242, 255)
            st.phase == com.nyro.companion.agent.Phase.EXECUTING && tool != null -> tool to android.graphics.Color.rgb(47, 123, 255)
            st.phase == com.nyro.companion.agent.Phase.THINKING -> (if (u.streaming && st.reply.isNotBlank()) "Responding…" else "Thinking…") to android.graphics.Color.rgb(122, 92, 255)
            speaking -> "Speaking" to android.graphics.Color.rgb(60, 240, 208)
            else -> "Ready" to cyan
        }
        val speed = if (u.tokensPerSec > 0f) "%.0f tok/s".format(u.tokensPerSec) else "– tok/s"
        val task = if (st.taskActive || u.task > 0) "task ${com.nyro.companion.agent.UsageMeter.fmt(u.task)} · " else ""
        val cache = if (u.cachePct > 0) " · cache ${u.cachePct}%" else ""
        return Triple(text, "${task}total ${com.nyro.companion.agent.UsageMeter.fmt(u.total)} tok · $speed$cache", color)
    }

    // ---------------- Foreground ----------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.channel_mode), NotificationManager.IMPORTANCE_LOW).apply {
            description = "Shown while NYRO Mode is on"; setShowBadge(false)
        })
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        fun action(a: String, req: Int) = PendingIntent.getService(this, req, Intent(this, NyroModeService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_nyro)
            .setContentTitle("NYRO Mode is on")
            .setContentText("Tap the orb to talk. Long-press it to stop everything.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Talk", action(ACTION_LISTEN, 3)).build())
            .addAction(Notification.Action.Builder(null, "Stop task", action(ACTION_STOP, 1)).build())
            .addAction(Notification.Action.Builder(null, "Normal Mode", action(ACTION_OFF, 2)).build())
            .build()
    }

    private fun goForeground(includeProjection: Boolean) {
        // specialUse exists from Android 14; on Android 13 a plain foreground service needs no type.
        val base = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val projection = if (includeProjection) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
        var types = base or projection
        if (NyroApp.graph.voice.hasMicPermission()) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            startForeground(NOTIF_ID, notification(), types)
        } catch (e: Exception) {
            // Mic type can be refused when (re)started from the background; keep the overlay alive without it.
            types = base or projection
            try {
                startForeground(NOTIF_ID, notification(), types)
            } catch (e2: Exception) {
                // The system refused a background restart entirely. Stay off; opening NYRO restores the mode.
                stopSelf(); return
            }
        }
        fgsTypes = types
    }

    val micInForeground: Boolean get() = fgsTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE != 0

    /** Re-evaluate FGS types, e.g. after the mic permission was granted while running. */
    fun refreshForeground() = goForeground(fgsTypes and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0)
    fun promoteForProjection() = goForeground(includeProjection = true)
    fun demoteFromProjection() { if (instance != null) main.post { runCatching { goForeground(includeProjection = false) } } }

    // ---------------- Windows ----------------

    private fun overlayParams(w: Int, h: Int, flags: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT,
    )

    private fun <T : View> T.owned(): T = apply {
        setViewTreeLifecycleOwner(this@NyroModeService)
        setViewTreeSavedStateRegistryOwner(this@NyroModeService)
        setViewTreeViewModelStoreOwner(this@NyroModeService)
    }

    private fun addEffects() {
        effectsParams = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            // Android 12+ blocks touches passing through other apps' overlays above 0.8 opacity.
            alpha = 0.8f
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        effects = EffectsView(this).also { wm.addView(it, effectsParams) }
    }

    private fun addOrb() {
        val s = NyroApp.graph.settings.current
        val dm = resources.displayMetrics
        orbParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (s.orbX < 0) dm.widthPixels - (88 * dm.density).toInt() else s.orbX
            y = s.orbY.coerceIn(0, dm.heightPixels - (120 * dm.density).toInt())
        }
        orbView = ComposeView(this).owned().apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@NyroModeService))
            setContent {
                NyroTheme {
                    OrbOverlay(
                        onTap = { NyroApp.graph.agent.startListening() },
                        onLongPress = { NyroApp.graph.agent.stop(); UiFeedback.notice("Stopped") },
                        onDragStart = { dragStartX = orbParams.x; dragStartY = orbParams.y },
                        onDragBy = { dx, dy -> moveOrbTo(dragStartX + dx.toInt(), dragStartY + dy.toInt()) },
                        onDragEnd = { snapOrb() },
                        onStop = { NyroApp.graph.agent.stop() },
                        orbOnRight = { orbParams.x > resources.displayMetrics.widthPixels / 2 },
                    )
                }
            }
        }
        wm.addView(orbView, orbParams)
    }

    private var dragStartX = 0
    private var dragStartY = 0

    private fun moveOrbTo(x: Int, y: Int) {
        val v = orbView ?: return
        val dm = resources.displayMetrics
        orbParams.x = x.coerceIn(-v.width / 3, dm.widthPixels - v.width * 2 / 3)
        orbParams.y = y.coerceIn(0, dm.heightPixels - v.height)
        runCatching { wm.updateViewLayout(v, orbParams) }
    }

    private fun isOnOrb(rawX: Float, rawY: Float): Boolean {
        val v = orbView ?: return false
        return rawX >= orbParams.x && rawX <= orbParams.x + v.width && rawY >= orbParams.y && rawY <= orbParams.y + v.height
    }

    private fun snapOrb() {
        val v = orbView ?: return
        val dm = resources.displayMetrics
        val target = if (orbParams.x + v.width / 2 < dm.widthPixels / 2) 0 else dm.widthPixels - v.width
        val startX = orbParams.x
        lifecycleScope.launch {
            val frames = 10
            for (i in 1..frames) {
                orbParams.x = startX + (target - startX) * i / frames
                runCatching { wm.updateViewLayout(v, orbParams) }
                delay(16)
            }
            NyroApp.graph.settings.setOrbPosition(orbParams.x, orbParams.y)
        }
    }

    private fun statusBarHeight(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) resources.getDimensionPixelSize(id) else (24 * resources.displayMetrics.density).toInt()
    }

    /** Approval card, placed under the status pill. It is only sized while an action waits for approval. */
    private fun addConfirm() {
        confirmParams = overlayParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = statusBarHeight() + (60 * resources.displayMetrics.density).toInt()
        }
        confirmView = ComposeView(this).owned().apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@NyroModeService))
            setContent { NyroTheme { ConfirmOverlay() } }
        }
        wm.addView(confirmView, confirmParams)
    }

    // ---------------- OverlayHost ----------------

    override fun showTap(x: Float, y: Float, long: Boolean) { main.post { effects?.tap(x, y, long) } }
    override fun showSwipe(x1: Float, y1: Float, x2: Float, y2: Float) { main.post { effects?.swipe(x1, y1, x2, y2) } }
    override fun highlight(r: Rect) { main.post { effects?.highlight(r) } }
    override fun focus(r: Rect) { main.post { effects?.focus(r) } }
    override fun typing(r: Rect) { main.post { effects?.typing(r) } }
    override fun scan() { main.post { effects?.scan() } }
    override fun setExecuting(on: Boolean) { main.post { effects?.setExecuting(on) } }
    override fun expandPanel() { /* the approval card follows agent state; nothing to open */ }

    override fun setTouchPassThrough(on: Boolean) {
        main.post {
            val v = orbView ?: return@post
            orbParams.flags = if (on) orbParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else orbParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            orbParams.alpha = if (on) 0.8f else 1f
            runCatching { wm.updateViewLayout(v, orbParams) }
        }
    }

    override suspend fun collapsePanel(): Boolean = false

    override suspend fun <T> hidden(block: suspend () -> T): T {
        suspendOnMain {
            orbView?.visibility = View.INVISIBLE; effects?.visibility = View.INVISIBLE
        }
        delay(120)
        try { return block() } finally {
            suspendOnMain { orbView?.visibility = View.VISIBLE; effects?.visibility = View.VISIBLE }
        }
    }

    private suspend fun suspendOnMain(block: () -> Unit) = suspendCancellableCoroutine<Unit> { cont ->
        main.post { runCatching(block); if (cont.isActive) cont.resume(Unit) }
    }

    companion object {
        @Volatile var instance: NyroModeService? = null
        const val CHANNEL = "nyro_mode"
        const val NOTIF_ID = 42
        const val ACTION_OFF = "com.nyro.companion.OFF"
        const val ACTION_STOP = "com.nyro.companion.STOP"
        const val ACTION_LISTEN = "com.nyro.companion.LISTEN"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, NyroModeService::class.java))
        }
        fun stop(context: Context) { context.stopService(Intent(context, NyroModeService::class.java)) }
    }
}
