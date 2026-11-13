package com.nyro.companion.tools

import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.NyroNotificationListener
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

class ToolRegistry {
    val tools: Map<String, Tool> = (screenTools() + deviceTools() + appTools() + memoryTools() + fileTools() + notificationTools() + undoTools() + skillTools() + healthTools() + planTools())
        .associateBy { it.name }

    val schemas: JsonArray by lazy { toolsJson(tools.values) }

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
