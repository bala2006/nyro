package com.nyro.companion.core

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.ScreenCaptureManager
import com.nyro.companion.agent.Agent
import com.nyro.companion.ai.AzureClient
import com.nyro.companion.data.NyroDatabase
import com.nyro.companion.data.SecureKeyStore
import com.nyro.companion.data.SettingsRepository
import com.nyro.companion.device.AppCatalog
import com.nyro.companion.device.DeviceInfo
import com.nyro.companion.overlay.NyroModeService
import com.nyro.companion.tools.ToolRegistry
import com.nyro.companion.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Manual dependency container. Small app, small graph: no DI framework needed. */
class AppGraph(val app: Application) {
    val context: Context get() = app
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(app, scope)
    val keys = SecureKeyStore(app)
    val db = NyroDatabase.create(app)
    val dao = db.dao()
    val ai = AzureClient(keyProvider = { keys.getApiKey() }, onResponsesRequired = { settings.markResponsesRequired(it) })
    val device = DeviceInfo(app)
    val apps = AppCatalog(app)
    val tools = ToolRegistry()
    val voice = VoiceEngine(app, settings)
    val agent = Agent(this)
    val mode = ModeController(this)

    fun accessibility(): NyroAccessibilityService? = NyroAccessibilityService.current

    fun onAccessibilityConnected() = mode.restoreIfNeeded()

    init {
        scope.launch(Dispatchers.IO) { dao.markOrphanedTasks() }
    }
}

/**
 * Normal Mode ⇄ NYRO Mode. The persisted flag is the source of truth, so the mode survives
 * process death: the sticky service, the app and the accessibility service all restore it.
 */
class ModeController(private val g: AppGraph) {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    fun canDrawOverlays() = Settings.canDrawOverlays(g.context)

    fun overlaySettingsIntent() = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${g.context.packageName}"))

    /** Returns false if the overlay permission is missing (caller should send the user to grant it). */
    suspend fun enable(): Boolean {
        if (!canDrawOverlays()) return false
        g.settings.setNyroMode(true)
        NyroModeService.start(g.context)
        return true
    }

    suspend fun disable() {
        g.settings.setNyroMode(false)
        g.agent.stop()
        ScreenCaptureManager.release()
        NyroModeService.stop(g.context)
        _active.value = false
    }

    fun onServiceStarted() { _active.value = true }
    fun onServiceStopped() { _active.value = false }

    fun restoreIfNeeded() {
        if (g.settings.current.nyroMode && NyroModeService.instance == null && canDrawOverlays()) {
            runCatching { NyroModeService.start(g.context) }
        }
        _active.value = NyroModeService.instance != null
    }
}
