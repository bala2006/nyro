package com.nyro.companion.tools

import com.nyro.companion.exec.PlanAction
import com.nyro.companion.exec.Workflows
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private fun str(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
private fun enumOf(desc: String, vararg values: String) = buildJsonObject {
    put("type", "string"); put("description", desc)
    put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
}

private val selectorSchema = buildJsonObject {
    put("type", "object")
    put("description", "Element by what it shows (never ids or coordinates). Fields: label|text|desc|view_id|role, match=contains|exact|starts, index (1-based)")
    put("properties", buildJsonObject {
        put("label", str("Text or description"))
        put("text", str("Visible text only"))
        put("desc", str("Description only"))
        put("view_id", str("Resource id after :id/"))
        put("role", str("Button, Input, Tab, Switch, ..."))
        put("match", enumOf("Default contains", "contains", "exact", "starts"))
        put("index", buildJsonObject { put("type", "integer") })
    })
}

/** Conditions repeat the target's selector fields; spelling the schema out four more times would cost prompt tokens on every request. */
private val conditionSchema = buildJsonObject {
    put("type", "object")
    put("description", "Exactly one key: app (name), visible/absent (a selector like target), changed:true, navigated:true")
    put("properties", buildJsonObject {
        put("app", buildJsonObject { put("type", "string") })
        put("visible", buildJsonObject { put("type", "object") })
        put("absent", buildJsonObject { put("type", "object") })
        put("changed", buildJsonObject { put("type", "boolean") })
        put("navigated", buildJsonObject { put("type", "boolean") })
    })
}

private val stepsSchema = buildJsonObject {
    put("type", "array")
    put("items", buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("do", enumOf("Action", *PlanAction.entries.map { it.wire }.toTypedArray()))
            put("app", str("open_app: name"))
            put("url", str("open_url: https URL"))
            put("page", str("open_settings: page"))
            put("text", str("type_text: text"))
            put("submit", buildJsonObject { put("type", "boolean"); put("description", "type_text: press enter/search after") })
            put("direction", enumOf("scroll: direction", "up", "down", "left", "right"))
            put("target", selectorSchema)
            put("require", buildJsonObject { put("type", "array"); put("description", "Must hold before the step"); put("items", conditionSchema) })
            put("expect", buildJsonObject { put("type", "array"); put("description", "Must hold after. Default: screen changed"); put("items", conditionSchema) })
            put("timeout_ms", buildJsonObject { put("type", "integer"); put("description", "Default 2500") })
            put("scroll_to_find", enumOf("tap/long_press: scroll until target appears", "up", "down"))
        })
        put("required", buildJsonArray { add(JsonPrimitive("do")) })
    })
}

/**
 * Schemas for the plan and workflow tools. They are executed by the agent (which owns the safety pipeline and the
 * confirmation UI), not by [Tool.run]. Their descriptions are static so the cached prompt prefix stays identical.
 */
fun planTools(): List<Tool> = listOf(
    object : Tool(
        "run_plan",
        "Run 2 to 12 predictable steps in ONE call. Targets are labels/roles, each step says what should be true after it (expect). NYRO resolves targets on the live screen, applies the normal safety checks, verifies each step, and returns only when something unexpected happens, a sensitive step ran, or the plan ends. Only plan steps visible from the current screen.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = {
            string("goal", "What the plan achieves", required = false)
            json("steps", "array", "Ordered steps", stepsSchema)
        },
    ) {
        override fun describe(args: Args) = args.str("goal")?.let { "Plan: ${it.take(60)}" } ?: "Running a plan"
        override suspend fun run(args: Args): ToolResult = ToolResult.fail("run_plan is executed by the NYRO agent loop.")
    },

    object : Tool(
        "run_workflow",
        "Run a built-in app flow that checks each screen (e.g. start a Duolingo lesson). Stops and returns the screen if the app shows something unknown.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { enum("workflow", "Workflow id", Workflows.all.map { it.id }) },
    ) {
        override fun describe(args: Args) = Workflows.find(args.str("workflow"))?.title ?: "Running a workflow"
        override suspend fun run(args: Args): ToolResult = ToolResult.fail("run_workflow is executed by the NYRO agent loop.")
    },
)
