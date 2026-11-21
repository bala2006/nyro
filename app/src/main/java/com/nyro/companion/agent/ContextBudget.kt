package com.nyro.companion.agent

import com.nyro.companion.ai.IMAGE_TOKENS_KEY
import com.nyro.companion.ai.Msg
import com.nyro.companion.ai.RAW_ITEMS_KEY
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** Token estimate and compaction of the running prompt. Pure, so the budget rules are testable. */
object ContextBudget {
    /** Only for picture messages from older sessions that did not record their own cost. */
    private const val DEFAULT_IMAGE_TOKENS = 1_200

    fun estimateTokens(messages: List<JsonObject>): Int = messages.sumOf { m ->
        var images = 0
        val text = when (val c = m["content"]) {
            is JsonPrimitive -> estimateText(c.contentOrNull.orEmpty())
            is JsonArray -> c.sumOf { part ->
                val o = part.jsonObject
                if ((o["type"] as? JsonPrimitive)?.contentOrNull == "image_url") { images++; 0 }
                else estimateText((o["text"] as? JsonPrimitive)?.contentOrNull.orEmpty())
            }
            else -> 0
        }
        val recorded = (m[IMAGE_TOKENS_KEY] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val imageTokens = if (images == 0) 0 else if (recorded > 0) recorded else images * DEFAULT_IMAGE_TOKENS
        text + imageTokens + estimateText(
            (m["tool_calls"]?.toString() ?: "") + (m[RAW_ITEMS_KEY]?.toString() ?: ""),
        )
    }

    /**
     * About 4 characters per token for Latin text. Devanagari and other non-Latin scripts are closer to one
     * token per character, so they are measured by UTF-8 bytes (3 bytes per character) instead of characters.
     * Over-estimating only compacts slightly earlier; under-estimating overflows the window.
     */
    fun estimateText(s: String): Int {
        if (s.isEmpty()) return 0
        val nonAscii = s.count { it.code > 127 }
        return if (nonAscii * 5 > s.length) s.toByteArray(Charsets.UTF_8).size / 3 else s.length / 4
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
