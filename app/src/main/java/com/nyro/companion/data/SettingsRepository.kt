package com.nyro.companion.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

/** How NYRO talks to Azure. Each value maps to a distinct, documented REST contract. */
enum class ProviderType(val label: String, val help: String) {
    AZURE_OPENAI(
        "Azure OpenAI (deployment + api-version)",
        "POST {endpoint}/openai/deployments/{deployment}/chat/completions?api-version=…  · header api-key"
    ),
    AZURE_OPENAI_V1(
        "Azure OpenAI v1 API",
        "POST {endpoint}/openai/v1/chat/completions  · body model={deployment} · no api-version needed"
    ),
    AZURE_AI_FOUNDRY(
        "Azure AI Foundry Models (model inference)",
        "POST {endpoint}/models/chat/completions?api-version=…  · body model={deployment}"
    );
}

/** Which REST API family to use for chat turns. */
enum class WireApi(val label: String) {
    AUTO("Auto"),
    CHAT("Chat Completions"),
    RESPONSES("Responses"),
}

data class ProviderConfig(
    val type: ProviderType = ProviderType.AZURE_OPENAI,
    val endpoint: String = "",
    val deployment: String = "",
    val apiVersion: String = DEFAULT_API_VERSION,
    val visionDeployment: String = "",
    val wireApi: WireApi = WireApi.AUTO,
    /** "" = model default; otherwise none/minimal/low/medium/high. */
    val reasoningEffort: String = "",
) {

    val isComplete: Boolean get() = endpoint.isNotBlank() && deployment.isNotBlank()

    companion object {
        const val DEFAULT_API_VERSION = "2024-10-21"
        const val DEFAULT_FOUNDRY_API_VERSION = "2024-05-01-preview"
    }
}

data class NyroSettings(
    val provider: ProviderConfig = ProviderConfig(),
    val nyroMode: Boolean = false,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val voiceName: String = "",
    val speakReplies: Boolean = true,
    val continuousConversation: Boolean = false,
    val allowNetworkRecognizer: Boolean = false,
    val recognitionLanguage: String = "",
    val confirmMediumRisk: Boolean = true,
    val allowScreenshots: Boolean = true,
    /** Resolve simple requests (open an app, battery, storage, notifications, settings pages...) on the phone without calling Azure. */
    val fastLocalCommands: Boolean = true,
    /** Dark is the default; false selects the light palette. */
    val darkTheme: Boolean = true,
    /** Prices per 1M tokens, used only for the cost estimate on the Activity tab. Edit to match your Azure pricing. */
    val priceInput: Float = 0.10f,
    val priceCached: Float = 0.01f,
    val priceOutput: Float = 0.50f,
    /** Orb look: size multiplier (0.7–1.4), opacity (0.35–1.0), compact dot, and docking to screen edges. */
    val orbScale: Float = 1.0f,
    val orbOpacity: Float = 1.0f,
    val orbCompact: Boolean = false,
    val dockEdges: Boolean = true,
    /** Roam: the companion moves around the screen and checks the area while NYRO works. */
    val roamMascot: Boolean = true,
    /** Reply length: short, natural or detailed. */
    val responseLength: String = "natural",
    /** Voice style preset: calm, friendly or brisk (sets speech rate and pitch). */
    val voiceStyle: String = "friendly",
    /** Standing instructions for NYRO (free text). */
    val customInstructions: String = "",
    /** Personal commands, one per line: trigger, a tab, then the instruction it expands to. */
    val commands: String = "",
    val orbX: Int = -1,
    /** Saved drop point; -1 means the resting corner (bottom right). */
    val orbY: Int = -1,
    val lastConnectionStatus: String = "",
    val capabilityInfo: String = "",
)

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "nyro_settings")

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object K {
        val providerType = stringPreferencesKey("provider_type")
        val endpoint = stringPreferencesKey("endpoint")
        val deployment = stringPreferencesKey("deployment")
        val apiVersion = stringPreferencesKey("api_version")
        val visionDeployment = stringPreferencesKey("vision_deployment")
        val wireApi = stringPreferencesKey("wire_api")
        val reasoning = stringPreferencesKey("reasoning_effort")
        val nyroMode = booleanPreferencesKey("nyro_mode")
        val speechRate = floatPreferencesKey("speech_rate")
        val speechPitch = floatPreferencesKey("speech_pitch")
        val voiceName = stringPreferencesKey("voice_name")
        val speakReplies = booleanPreferencesKey("speak_replies")
        val continuous = booleanPreferencesKey("continuous")
        val allowNetRec = booleanPreferencesKey("allow_net_rec")
        val recLang = stringPreferencesKey("rec_lang")
        val confirmMedium = booleanPreferencesKey("confirm_medium")
        val allowShots = booleanPreferencesKey("allow_screenshots")
        val fastLocal = booleanPreferencesKey("fast_local_commands")
        val darkTheme = booleanPreferencesKey("dark_theme")
        val priceIn = floatPreferencesKey("price_input")
        val priceCached = floatPreferencesKey("price_cached")
        val priceOut = floatPreferencesKey("price_output")
        val orbScale = floatPreferencesKey("orb_scale")
        val orbOpacity = floatPreferencesKey("orb_opacity")
        val orbCompact = booleanPreferencesKey("orb_compact")
        val dockEdges = booleanPreferencesKey("dock_edges")
        val roamMascot = booleanPreferencesKey("roam_mascot")
        val responseLength = stringPreferencesKey("response_length")
        val voiceStyle = stringPreferencesKey("voice_style")
        val customInstructions = stringPreferencesKey("custom_instructions")
        val commands = stringPreferencesKey("personal_commands")
        val orbX = intPreferencesKey("orb_x")
        val orbY = intPreferencesKey("orb_y")
        val lastStatus = stringPreferencesKey("last_status")
        val capability = stringPreferencesKey("capability")
    }

    private val store = context.dataStore

    val settings: StateFlow<NyroSettings> = store.data.map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, runBlocking { store.data.first().toSettings() })

    val current: NyroSettings get() = settings.value

    private fun Preferences.toSettings() = NyroSettings(
        provider = ProviderConfig(
            type = this[K.providerType]?.let { runCatching { ProviderType.valueOf(it) }.getOrNull() } ?: ProviderType.AZURE_OPENAI,
            endpoint = this[K.endpoint] ?: "",
            deployment = this[K.deployment] ?: "",
            apiVersion = this[K.apiVersion] ?: ProviderConfig.DEFAULT_API_VERSION,
            visionDeployment = this[K.visionDeployment] ?: "",
            wireApi = this[K.wireApi]?.let { runCatching { WireApi.valueOf(it) }.getOrNull() } ?: WireApi.AUTO,
            reasoningEffort = this[K.reasoning] ?: "",
        ),
        nyroMode = this[K.nyroMode] ?: false,
        speechRate = this[K.speechRate] ?: 1.0f,
        speechPitch = this[K.speechPitch] ?: 1.0f,
        voiceName = this[K.voiceName] ?: "",
        speakReplies = this[K.speakReplies] ?: true,
        continuousConversation = this[K.continuous] ?: false,
        allowNetworkRecognizer = this[K.allowNetRec] ?: false,
        recognitionLanguage = this[K.recLang] ?: "",
        confirmMediumRisk = this[K.confirmMedium] ?: true,
        allowScreenshots = this[K.allowShots] ?: true,
        fastLocalCommands = this[K.fastLocal] ?: true,
        darkTheme = this[K.darkTheme] ?: true,
        priceInput = this[K.priceIn] ?: 0.10f,
        priceCached = this[K.priceCached] ?: 0.01f,
        priceOutput = this[K.priceOut] ?: 0.50f,
        orbScale = this[K.orbScale] ?: 1.0f,
        orbOpacity = this[K.orbOpacity] ?: 1.0f,
        orbCompact = this[K.orbCompact] ?: false,
        dockEdges = this[K.dockEdges] ?: true,
        roamMascot = this[K.roamMascot] ?: true,
        responseLength = this[K.responseLength] ?: "natural",
        voiceStyle = this[K.voiceStyle] ?: "friendly",
        customInstructions = this[K.customInstructions] ?: "",
        commands = this[K.commands] ?: "",
        orbX = this[K.orbX] ?: -1,
        orbY = this[K.orbY] ?: -1,
        lastConnectionStatus = this[K.lastStatus] ?: "",
        capabilityInfo = this[K.capability] ?: "",
    )

    suspend fun saveProvider(cfg: ProviderConfig) = store.edit {
        it[K.providerType] = cfg.type.name
        it[K.endpoint] = cfg.endpoint
        it[K.deployment] = cfg.deployment
        it[K.apiVersion] = cfg.apiVersion
        it[K.visionDeployment] = cfg.visionDeployment
        it[K.wireApi] = cfg.wireApi.name
        it[K.reasoning] = cfg.reasoningEffort
    }


    suspend fun clearProvider() = store.edit {
        it.remove(K.endpoint); it.remove(K.deployment); it.remove(K.apiVersion)
        it.remove(K.visionDeployment); it.remove(K.lastStatus); it.remove(K.capability)
        it.remove(K.wireApi); it.remove(K.reasoning)
    }

    suspend fun setNyroMode(on: Boolean) = store.edit { it[K.nyroMode] = on }
    suspend fun setDarkTheme(on: Boolean) = store.edit { it[K.darkTheme] = on }
    suspend fun saveOrbLook(scale: Float, opacity: Float, compact: Boolean, dock: Boolean) = store.edit {
        it[K.orbScale] = scale; it[K.orbOpacity] = opacity; it[K.orbCompact] = compact; it[K.dockEdges] = dock
    }

    suspend fun saveInstructions(text: String) = store.edit { it[K.customInstructions] = text.take(1500) }

    suspend fun saveCommands(text: String) = store.edit { it[K.commands] = text.take(4000) }

    suspend fun savePrices(input: Float, cached: Float, output: Float) = store.edit {
        it[K.priceIn] = input; it[K.priceCached] = cached; it[K.priceOut] = output
    }
    suspend fun setConnectionStatus(status: String, capability: String) = store.edit {
        it[K.lastStatus] = status; it[K.capability] = capability
    }
    suspend fun setOrbPosition(x: Int, y: Int) = store.edit { it[K.orbX] = x; it[K.orbY] = y }

    suspend fun setRoamMascot(on: Boolean) = store.edit { it[K.roamMascot] = on }

    suspend fun update(block: (NyroSettings) -> NyroSettings) {
        val n = block(current)
        store.edit {
            it[K.speechRate] = n.speechRate
            it[K.speechPitch] = n.speechPitch
            it[K.voiceName] = n.voiceName
            it[K.speakReplies] = n.speakReplies
            it[K.continuous] = n.continuousConversation
            it[K.allowNetRec] = n.allowNetworkRecognizer
            it[K.recLang] = n.recognitionLanguage
            it[K.confirmMedium] = n.confirmMediumRisk
            it[K.allowShots] = n.allowScreenshots
            it[K.fastLocal] = n.fastLocalCommands
            it[K.responseLength] = n.responseLength
            it[K.voiceStyle] = n.voiceStyle
        }
    }
}
