package com.nyro.companion.tools

import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.NyroNotificationListener
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class ToolRegistry {
    val tools: Map<String, Tool> = (
        screenTools() + deviceTools() + appTools() + memoryTools() + fileTools() + notificationTools() +
            undoTools() + skillTools() + healthTools() + planTools() + discoveryTools()
        ).associateBy { it.name }

    /** Tools loaded by find_tools during this conversation, in the order they were discovered. */
    private val activated = LinkedHashSet<String>()

    @Volatile private var cachedKey: String = ""
    @Volatile private var cachedSchemas: JsonArray = toolsJson(emptyList())

    /**
     * The schemas sent with the current request: the core set plus anything find_tools loaded. Stable between
     * calls with the same set, so the prompt-cache prefix keeps matching.
     */
    val schemas: JsonArray
        get() {
            val names = activeNames()
            val key = names.joinToString(",")
            if (key == cachedKey) return cachedSchemas
            val fresh = toolsJson(names.mapNotNull { tools[it] })
            cachedKey = key
            cachedSchemas = fresh
            return fresh
        }


    fun activeNames(): List<String> =
        CORE.filter { tools.containsKey(it) } + activated.filter { it !in CORE && tools.containsKey(it) }

    /** Makes discovered tools available for the rest of the conversation. Returns how many were new. */
    @Synchronized
    fun activate(names: Collection<String>): Int {
        var added = 0
        for (name in names) {
            if (tools.containsKey(name) && name !in CORE && activated.add(name)) added++
        }
        return added
    }

    /** A new conversation starts from the core set again. */
    @Synchronized
    fun resetActivated() { activated.clear() }

    /**
     * Tools that match [query] and are not loaded yet, best first. Tools whose permission is missing are never
     * offered, so the model cannot load a tool that would only fail.
     */
    fun search(query: String, limit: Int = 6): List<Tool> {
        val q = query.lowercase().trim()
        if (q.isEmpty()) return emptyList()
        val words = q.split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        val loaded = activeNames().toSet()
        return tools.values
            .filter { it.name !in loaded && unmet(it) == null }
            .map { it to relevance(it, q, words) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun relevance(t: Tool, q: String, words: List<String>): Int {
        val name = t.name.replace('_', ' ')
        val text = (t.description + " " + t.params.joinToString(" ") { it.name }).lowercase()
        var score = 0
        if (name.contains(q)) score += 100 else if (q.contains(name)) score += 80
        for (w in words) {
            if (name.contains(w)) score += 40
            if (text.contains(w)) score += 8
        }
        return score
    }

    fun get(name: String): Tool? = tools[name]

    fun parseArgs(raw: String): Result<Args> = runCatching {
        val trimmed = raw.trim().ifEmpty { "{}" }
        Args(Json.parseToJsonElement(trimmed).jsonObject)
    }

    fun args(vararg pairs: Pair<String, Any?>): Args = Args(JsonObject(pairs.associate { (k, v) ->
        k to when (v) {
            null -> kotlinx.serialization.json.JsonNull
            is Number -> kotlinx.serialization.json.JsonPrimitive(v)
            is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
            else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
        }
    }))

    /** Returns the first unmet requirement for [tool], checked against live system state. */
    fun unmet(tool: Tool): Requirement? = tool.requires.firstOrNull { r ->
        when (r) {
            Requirement.ACCESSIBILITY -> NyroAccessibilityService.current == null
            Requirement.NOTIFICATIONS -> NyroNotificationListener.instance == null
            Requirement.MEDIA -> !hasMediaPermission()
            Requirement.NYRO_MODE -> false
        }
    }
}

/** Sent on every request: the handful of tools a phone task needs most, plus discovery itself. */
private val CORE = listOf(
    "read_screen", "find_elements", "tap_element", "tap_text", "type_text", "scroll", "tap_point",
    "press_back", "press_home", "open_app", "take_screenshot", "wait",
    "run_plan", "run_workflow", "find_tools", "remember", "recall",
)
