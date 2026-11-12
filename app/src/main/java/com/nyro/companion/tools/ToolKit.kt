package com.nyro.companion.tools

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

enum class Risk { LOW, MEDIUM, HIGH }

/** Capabilities a tool depends on; checked locally before execution. */
enum class Requirement(val label: String) {
    ACCESSIBILITY("NYRO Phone Control (Accessibility) is not enabled. Ask the user to enable it in NYRO → Permissions."),
    NOTIFICATIONS("Notification access is not granted. Ask the user to grant it in NYRO → Permissions."),
    MEDIA("Media file permission is not granted. Ask the user to grant it in NYRO → Permissions."),
    NYRO_MODE("NYRO Mode is off."),
}

data class ToolResult(
    val text: String,
    val ok: Boolean = true,
    /** Base64 JPEG to show the model (vision). */
    val imageJpeg: String? = null,
) {
    companion object {
        fun fail(msg: String) = ToolResult("FAILED: $msg", ok = false)
    }
}

/** A risk assessment for a specific invocation; [reason] is shown to the user in the confirmation prompt. */
data class RiskAssessment(val risk: Risk, val reason: String)

class Param(val name: String, val type: String, val description: String, val required: Boolean, val enumValues: List<String>? = null)

class ParamsBuilder {
    val list = mutableListOf<Param>()
    fun string(n: String, d: String, required: Boolean = true) { list += Param(n, "string", d, required) }
    fun int(n: String, d: String, required: Boolean = true) { list += Param(n, "integer", d, required) }
    fun number(n: String, d: String, required: Boolean = true) { list += Param(n, "number", d, required) }
    fun bool(n: String, d: String, required: Boolean = false) { list += Param(n, "boolean", d, required) }
    fun enum(n: String, d: String, values: List<String>, required: Boolean = true) { list += Param(n, "string", d, required, values) }
}

abstract class Tool(
    val name: String,
    val description: String,
    val baseRisk: Risk = Risk.LOW,
    val requires: Set<Requirement> = emptySet(),
    /** True for tools that act on or read the foreground UI (overlay panel gets collapsed first). */
    val touchesScreen: Boolean = false,
    params: ParamsBuilder.() -> Unit = {},
) {
    val params: List<Param> = ParamsBuilder().apply(params).list

    /** Human-readable label for the task timeline. */
    open fun describe(args: Args): String = name.replace('_', ' ')

    /** Per-call risk; defaults to the tool's base risk. */
    open fun assess(args: Args): RiskAssessment = RiskAssessment(baseRisk, describe(args))

    abstract suspend fun run(args: Args): ToolResult

    fun schema(): JsonObject = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", description)
            put("parameters", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    params.forEach { p ->
                        put(p.name, buildJsonObject {
                            put("type", p.type); put("description", p.description)
                            p.enumValues?.let { ev -> put("enum", buildJsonArray { ev.forEach { add(JsonPrimitive(it)) } }) }
                        })
                    }
                })
                put("required", buildJsonArray { params.filter { it.required }.forEach { add(JsonPrimitive(it.name)) } })
            })
        })
    }

    /** Local validation of model-provided arguments. Returns an error message or null. */
    fun validate(args: Args): String? {
        for (p in params) {
            val v = args.raw[p.name]
            if (v == null || (v is JsonPrimitive && v.contentOrNull == null)) {
                if (p.required) return "Missing required argument '${p.name}'."
                continue
            }
            val prim = v as? JsonPrimitive ?: return "Argument '${p.name}' must be a ${p.type}."
            when (p.type) {
                "integer", "number" -> if (prim.doubleOrNull == null) return "Argument '${p.name}' must be a number."
                "boolean" -> if (prim.booleanOrNull == null && prim.contentOrNull !in listOf("true", "false")) return "Argument '${p.name}' must be true or false."
            }
            if (p.enumValues != null && prim.contentOrNull !in p.enumValues) return "Argument '${p.name}' must be one of ${p.enumValues}."
        }
        return null
    }
}

class Args(val raw: JsonObject) {
    fun str(k: String): String? = (raw[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
    fun int(k: String): Int? = (raw[k] as? JsonPrimitive)?.doubleOrNull?.toInt()
    fun num(k: String): Double? = (raw[k] as? JsonPrimitive)?.doubleOrNull
    fun bool(k: String): Boolean? = (raw[k] as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }
}

fun toolsJson(tools: Collection<Tool>): JsonArray = buildJsonArray { tools.forEach { add(it.schema()) } }

/** Keyword-based escalation for UI actions on sensitive controls. */
object SensitiveUi {
    private val high = Regex(
        "(?i)\\b(pay|payment|buy|purchase|place order|order now|checkout|check out|transfer|send money|confirm payment|delete|remove account|erase|uninstall|factory reset|reset phone|wipe|subscribe|book now|donate|withdraw|unlock bootloader|disable security|turn off screen lock)\\b"
    )
    private val medium = Regex(
        "(?i)\\b(send|post|share|publish|submit|reply|call|upload|install|allow|accept|agree|grant|save|apply|sign out|log out|logout|follow|unfollow|block|report|forward|archive|enable|disable)\\b"
    )
    private val sensitivePkg = Regex("(?i)(bank|\\.pay|pay\\.|wallet|upi|paisa|phonepe|paytm|gpay|finance|crypto|trading|broker|\\.sbi|hdfc|icici|axisbank|kotak|\\.nbu)")

    fun classify(label: String, pkg: String, isToggle: Boolean = false): RiskAssessment? {
        val text = label.trim()
        if (high.containsMatchIn(text)) return RiskAssessment(Risk.HIGH, "Tap \"$text\" (possible payment, purchase, deletion or security change)")
        if (isFinancial(pkg)) return RiskAssessment(Risk.HIGH, "Act inside a financial app ($pkg): \"$text\"")
        if (medium.containsMatchIn(text)) return RiskAssessment(Risk.MEDIUM, "Tap \"$text\" (sends, shares or changes something)")
        if (isToggle && pkg.contains("settings")) return RiskAssessment(Risk.MEDIUM, "Change a system setting: \"$text\"")
        return null
    }

    fun isFinancial(pkg: String) = sensitivePkg.containsMatchIn(pkg)

    /** Words in the user's request that count as explicitly asking for a given action verb. */
    private val synonyms = mapOf(
        "send" to listOf("send", "text", "message", "msg", "tell", "reply", "dm", "say"),
        "reply" to listOf("reply", "respond", "answer", "send", "tell", "text"),
        "share" to listOf("share", "send"),
        "post" to listOf("post", "publish", "upload", "tweet", "share"),
        "publish" to listOf("publish", "post"),
        "upload" to listOf("upload", "post", "send"),
        "submit" to listOf("submit", "send", "fill"),
        "call" to listOf("call", "ring", "dial", "phone"),
        "forward" to listOf("forward", "send", "share"),
        "follow" to listOf("follow"), "unfollow" to listOf("unfollow"),
        "save" to listOf("save"), "apply" to listOf("apply"),
        "install" to listOf("install", "download"),
        "enable" to listOf("enable", "turn on", "switch on", "activate"),
        "disable" to listOf("disable", "turn off", "switch off", "deactivate"),
        "archive" to listOf("archive"), "block" to listOf("block"), "report" to listOf("report"),
        "accept" to listOf("accept"), "agree" to listOf("agree", "accept"), "allow" to listOf("allow", "grant"), "grant" to listOf("grant", "allow"),
        "lock" to listOf("lock"),
    )

    /**
     * True when the user's own request already asked for this medium-risk action (e.g. tapping
     * "Send" after "send hello to Pranav"). High-risk actions are never covered.
     */
    fun requestCovers(request: String, a: RiskAssessment, toolName: String): Boolean {
        if (a.risk != Risk.MEDIUM) return false
        val req = request.lowercase()
        val verb = medium.find(a.reason)?.value?.lowercase()
            ?: toolName.substringBefore('_').lowercase()
        val words = synonyms[verb] ?: listOf(verb)
        if (a.reason.startsWith("Change a system setting")) {
            return listOf("turn on", "turn off", "switch", "enable", "disable", "toggle", "set ").any { req.contains(it) }
        }
        return words.any { w -> Regex("\\b" + Regex.escape(w) + "\\b").containsMatchIn(req) }
    }
}
