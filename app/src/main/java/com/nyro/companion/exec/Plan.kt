package com.nyro.companion.exec

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** The only actions a plan may contain. Everything else must be called directly so it gets its own model turn. */
enum class PlanAction(val wire: String, val tool: String?, val changesScreen: Boolean) {
    OPEN_APP("open_app", "open_app", true),
    OPEN_URL("open_url", "open_url", true),
    OPEN_SETTINGS("open_settings", "open_settings", true),
    PRESS_BACK("press_back", "press_back", true),
    PRESS_HOME("press_home", "press_home", true),
    TAP("tap", "tap_element", true),
    LONG_PRESS("long_press", "long_press_element", true),
    TYPE_TEXT("type_text", "type_text", false),
    SCROLL("scroll", "scroll", true),
    WAIT_FOR("wait_for", null, false);

    val needsTarget: Boolean get() = this == TAP || this == LONG_PRESS

    companion object {
        fun fromWire(s: String?): PlanAction? = entries.firstOrNull { it.wire == s }
    }
}

data class PlanStep(
    val id: String,
    val action: PlanAction,
    val app: String? = null,
    val url: String? = null,
    val page: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val submit: Boolean? = null,
    val target: Selector? = null,
    val require: List<Condition> = emptyList(),
    val expect: List<Condition> = emptyList(),
    val timeoutMs: Long = PlanLimits.DEFAULT_TIMEOUT_MS,
    /** Scroll this way (up to [PlanLimits.MAX_SCROLL_TO_FIND] times) while the target is not on screen. */
    val scrollToFind: String? = null,
) {
    /** Short label for logs and the task timeline. */
    fun describe(): String = when (action) {
        PlanAction.OPEN_APP -> "Open ${app.orEmpty()}"
        PlanAction.OPEN_URL -> "Open ${url.orEmpty()}"
        PlanAction.OPEN_SETTINGS -> "Open ${page.orEmpty()} settings"
        PlanAction.PRESS_BACK -> "Go back"
        PlanAction.PRESS_HOME -> "Go home"
        PlanAction.TAP -> "Tap ${target?.describe().orEmpty()}"
        PlanAction.LONG_PRESS -> "Long-press ${target?.describe().orEmpty()}"
        PlanAction.TYPE_TEXT -> "Type \"${text.orEmpty().take(30)}\""
        PlanAction.SCROLL -> "Scroll ${direction.orEmpty()}"
        PlanAction.WAIT_FOR -> "Wait for ${expect.firstOrNull()?.describe().orEmpty()}"
    }

    /** Stable key used by loop detection: same action on the same target is the same key across screens. */
    fun actionKey(): ActionKey = ActionKey(
        action.wire,
        when (action) {
            PlanAction.OPEN_APP -> app.orEmpty().lowercase()
            PlanAction.OPEN_URL -> url.orEmpty().lowercase()
            PlanAction.OPEN_SETTINGS -> page.orEmpty().lowercase()
            PlanAction.TAP, PlanAction.LONG_PRESS -> target?.describe().orEmpty().lowercase()
            PlanAction.TYPE_TEXT -> text.orEmpty()
            PlanAction.SCROLL -> direction.orEmpty()
            else -> ""
        },
    )
}

data class Plan(val goal: String, val steps: List<PlanStep>)

object PlanLimits {
    const val MAX_STEPS = 12
    const val DEFAULT_TIMEOUT_MS = 2500L
    const val MAX_STEP_TIMEOUT_MS = 15_000L
    const val MAX_PLAN_MS = 90_000L
    const val MAX_SCROLL_TO_FIND = 4
    const val MAX_CONSECUTIVE_BACK = 3
}

/** Reads a plan from tool arguments (`goal` and `steps`). Collects every problem, not just the first. */
object PlanParser {
    class Result(val plan: Plan?, val errors: List<String>)

    fun parse(args: JsonObject): Result {
        val errors = ArrayList<String>()
        val goal = (args["goal"] as? JsonPrimitive)?.contentOrNull.orEmpty().take(200)
        val rawSteps = args["steps"] as? JsonArray
        if (rawSteps == null) return Result(null, listOf("'steps' must be an array of step objects."))
        val steps = ArrayList<PlanStep>()
        rawSteps.forEachIndexed { i, el ->
            val n = i + 1
            val o = el as? JsonObject
            if (o == null) { errors += "Step $n must be an object."; return@forEachIndexed }
            val action = PlanAction.fromWire(o.str("do"))
            if (action == null) {
                errors += "Step $n: 'do' must be one of ${PlanAction.entries.joinToString { it.wire }} (got ${o.str("do")?.let { "\"$it\"" } ?: "nothing"}). Call other tools directly."
                return@forEachIndexed
            }
            val target = (o["target"] as? JsonObject)?.let { selector(it, errors, "Step $n target") }
            val require = conditions(o["require"], errors, "Step $n require")
            val expect = conditions(o["expect"], errors, "Step $n expect")
            val timeout = (o["timeout_ms"] as? JsonPrimitive)?.doubleOrNull?.toLong() ?: PlanLimits.DEFAULT_TIMEOUT_MS
            steps += PlanStep(
                id = o.str("id")?.take(24) ?: "s$n",
                action = action,
                app = o.str("app"), url = o.str("url"), page = o.str("page"),
                text = (o["text"] as? JsonPrimitive)?.contentOrNull, direction = o.str("direction"),
                submit = (o["submit"] as? JsonPrimitive)?.booleanOrNull,
                target = target, require = require, expect = expect,
                timeoutMs = timeout.coerceIn(300, PlanLimits.MAX_STEP_TIMEOUT_MS),
                scrollToFind = o.str("scroll_to_find"),
            )
        }
        return Result(if (errors.isEmpty()) Plan(goal, steps) else null, errors)
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun selector(o: JsonObject, errors: MutableList<String>, where: String): Selector {
        val mode = when (o.str("match")?.lowercase()) {
            null, "contains" -> MatchMode.CONTAINS
            "exact" -> MatchMode.EXACT
            "starts", "starts_with" -> MatchMode.STARTS
            else -> { errors += "$where: 'match' must be exact, starts or contains."; MatchMode.CONTAINS }
        }
        return Selector(
            label = o.str("label"), text = o.str("text"), desc = o.str("desc"), viewId = o.str("view_id"),
            role = o.str("role"), mode = mode, index = (o["index"] as? JsonPrimitive)?.doubleOrNull?.toInt(),
            editable = (o["editable"] as? JsonPrimitive)?.booleanOrNull,
        )
    }

    private fun conditions(el: JsonElement?, errors: MutableList<String>, where: String): List<Condition> {
        if (el == null || el is JsonNull) return emptyList()
        val arr = el as? JsonArray ?: run { errors += "$where must be an array."; return emptyList() }
        return arr.mapIndexedNotNull { i, c ->
            val o = c as? JsonObject ?: run { errors += "$where[${i + 1}] must be an object."; return@mapIndexedNotNull null }
            condition(o, errors, "$where[${i + 1}]")
        }
    }

    fun condition(o: JsonObject, errors: MutableList<String>, where: String): Condition? {
        o.str("app")?.let { return Condition.App(it) }
        (o["visible"] as? JsonObject)?.let { return Condition.Visible(selector(it, errors, where)) }
        (o["absent"] as? JsonObject)?.let { return Condition.Absent(selector(it, errors, where)) }
        if ((o["changed"] as? JsonPrimitive)?.booleanOrNull == true) return Condition.Changed
        if ((o["navigated"] as? JsonPrimitive)?.booleanOrNull == true) return Condition.Navigated
        errors += "$where needs one of: app, visible{selector}, absent{selector}, changed:true, navigated:true."
        return null
    }
}

/** Lets the validator check tool arguments without depending on Android or the tool registry. */
fun interface ToolSpec {
    /** Error message, or null when [tool] exists and [args] are valid. */
    fun validate(tool: String, args: JsonObject): String?
}

/** Static checks done before anything runs. Risk is NOT decided here: it is assessed per step on the real element. */
object PlanValidator {
    fun validate(plan: Plan, tools: ToolSpec): List<String> {
        val errors = ArrayList<String>()
        if (plan.steps.isEmpty()) errors += "The plan has no steps."
        if (plan.steps.size > PlanLimits.MAX_STEPS) errors += "At most ${PlanLimits.MAX_STEPS} steps per plan (got ${plan.steps.size}). Plan the next part after the checkpoint."
        val ids = HashSet<String>()
        var backRun = 0
        plan.steps.forEachIndexed { i, s ->
            val n = i + 1
            if (!ids.add(s.id)) errors += "Step $n: duplicate id '${s.id}'."
            when (s.action) {
                PlanAction.TAP, PlanAction.LONG_PRESS -> {
                    if (s.target == null || s.target.isEmpty) errors += "Step $n (${s.action.wire}): 'target' needs label, text, desc or view_id. Ids and coordinates are not allowed in plans."
                }
                PlanAction.WAIT_FOR -> if (s.expect.isEmpty()) errors += "Step $n (wait_for): needs at least one 'expect' condition."
                PlanAction.TYPE_TEXT -> if (s.text == null) errors += "Step $n (type_text): 'text' is required."
                PlanAction.OPEN_APP -> if (s.app.isNullOrBlank()) errors += "Step $n (open_app): 'app' is required."
                PlanAction.OPEN_URL -> if (s.url.isNullOrBlank()) errors += "Step $n (open_url): 'url' is required."
                PlanAction.OPEN_SETTINGS -> if (s.page.isNullOrBlank()) errors += "Step $n (open_settings): 'page' is required."
                PlanAction.SCROLL -> if (s.direction !in setOf("up", "down", "left", "right")) errors += "Step $n (scroll): 'direction' must be up, down, left or right."
                else -> Unit
            }
            if (s.scrollToFind != null && s.scrollToFind !in setOf("up", "down")) errors += "Step $n: scroll_to_find must be up or down."
            if (s.scrollToFind != null && !s.action.needsTarget) errors += "Step $n: scroll_to_find only applies to tap and long_press."
            s.action.tool?.let { tool ->
                val probe = toolArgs(s, elementId = 1)
                tools.validate(tool, probe)?.let { errors += "Step $n ($tool): $it" }
            }
            backRun = if (s.action == PlanAction.PRESS_BACK) backRun + 1 else 0
            if (backRun > PlanLimits.MAX_CONSECUTIVE_BACK) errors += "Step $n: more than ${PlanLimits.MAX_CONSECUTIVE_BACK} back presses in a row. Use press_home or open_app instead."
            if (i > 0) {
                val prev = plan.steps[i - 1]
                if (prev.action == s.action && prev.actionKey() == s.actionKey() && s.action != PlanAction.SCROLL && s.action != PlanAction.WAIT_FOR) {
                    errors += "Step $n repeats step $i (${s.describe()}). Repeating an action does not produce a different result; add an expectation or change the target."
                }
            }
        }
        return errors
    }

    /** Arguments for the underlying tool. [elementId] is filled by the executor after resolving the selector. */
    fun toolArgs(s: PlanStep, elementId: Int?): JsonObject = buildJsonObject {
        when (s.action) {
            PlanAction.OPEN_APP -> { put("name", s.app.orEmpty()); put("brief", true) }
            PlanAction.OPEN_URL -> put("url", s.url.orEmpty())
            PlanAction.OPEN_SETTINGS -> put("page", s.page.orEmpty())
            PlanAction.TAP, PlanAction.LONG_PRESS -> elementId?.let { put("element_id", it) }
            PlanAction.TYPE_TEXT -> {
                put("text", s.text.orEmpty()); elementId?.let { put("element_id", it) }; s.submit?.let { put("submit", it) }
            }
            PlanAction.SCROLL -> put("direction", s.direction.orEmpty())
            else -> Unit
        }
    }

    /** Default expectation so no screen-changing step runs without a check. */
    fun effectiveExpect(s: PlanStep): List<Condition> = when {
        s.expect.isNotEmpty() -> s.expect
        s.action.changesScreen || s.action == PlanAction.TYPE_TEXT -> listOf(Condition.Changed)
        else -> emptyList()
    }
}
