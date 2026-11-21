package com.nyro.companion.tools

import com.nyro.companion.NyroApp

/**
 * Tool discovery. Only the core set (see [ToolRegistry]) is sent on every request; everything else is loaded
 * on demand, because tool definitions were the largest part of every prompt and tool choice gets worse as the
 * list grows.
 */
fun discoveryTools(): List<Tool> = listOf(
    object : Tool(
        "find_tools",
        "Search the rest of the tool library and load what you need. Many tools are not loaded by default " +
            "(files, notifications, device info, timers, alarms, volume, flashlight, clipboard, web search, " +
            "settings pages, sharing, app list, health checks, memory forget). Call this with what you want to " +
            "do, e.g. \"set a timer\"; the tools appear from your next turn.",
        readOnly = true,
        params = { string("query", "What you want to do, in a few words") },
    ) {
        override fun describe(args: Args) = "Looking for tools: ${args.str("query")}"

        override suspend fun run(args: Args): ToolResult {
            val query = args.str("query") ?: return ToolResult.fail("Describe what you need to do.")
            val registry = NyroApp.graph.tools
            val hits = registry.search(query)
            if (hits.isEmpty()) {
                return ToolResult("No other tool matches \"$query\". Loaded now: ${registry.activeNames().joinToString(", ")}.")
            }
            val added = registry.activate(hits.map { it.name })
            val list = hits.joinToString("\n") { t ->
                val argsText = t.params.joinToString(", ") { p -> p.name + if (p.required) "" else "?" }
                "- ${t.name}: ${t.description} Arguments: ${argsText.ifEmpty { "none" }}"
            }
            return ToolResult("Loaded $added tool(s). You can call them from your next turn:\n$list")
        }
    },
)
