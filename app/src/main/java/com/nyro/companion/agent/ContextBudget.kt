package com.nyro.companion.agent

import com.nyro.companion.ai.Msg
import com.nyro.companion.ai.RAW_ITEMS_KEY
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Token estimate and compaction of the running prompt. Pure, so the budget rules are testable. */
object ContextBudget {
    /** Rough token count: about 4 characters per token, about 1000 per image, plus encrypted reasoning. */
    fun estimateTokens(messages: List<JsonObject>): Int = messages.sumOf { m ->
        val content = when (val c = m["content"]) {
            is JsonPrimitive -> (c.contentOrNull?.length ?: 0) / 4
            is JsonArray -> c.sumOf { part ->
                val o = part.jsonObject
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "image_url") 1000
                else ((o["text"] as? JsonPrimitive)?.contentOrNull?.length ?: 0) / 4
            }
            else -> 0
        }
        content + ((m["tool_calls"]?.toString()?.length ?: 0) + (m[RAW_ITEMS_KEY]?.toString()?.length ?: 0)) / 4
    }

    /**
     * Prompt caching needs the earlier messages to stay identical, so history is left alone until it reaches [compactAt].
     * Then the middle of the task is replaced with a summary built from the step log (no extra model call). The newest
     * exchanges stay verbatim, and the cut never separates a tool call from its output. Returns true if it compacted.
     */
    fun compact(messages: MutableList<JsonObject>, stepLog: String, compactAt: Int, keepTail: Int, maxSummaryChars: Int): Boolean {
        if (estimateTokens(messages) < compactAt || messages.size < keepTail + 4) return false
        var cut = messages.size - keepTail
        while (cut < messages.size && (messages[cut]["role"] as? JsonPrimitive)?.contentOrNull == "tool") cut++
        if (cut <= 2 || cut >= messages.size) return false
        val summary = "Earlier in this task (summarized to fit the context budget). Steps taken:\n" + stepLog.takeLast(maxSummaryChars)
        val head = messages.subList(0, 2).toList()
        val tail = messages.subList(cut, messages.size).toList()
        messages.clear()
        messages.addAll(head)
        messages += Msg.user(summary)
        messages.addAll(tail)
        return true
    }
}
