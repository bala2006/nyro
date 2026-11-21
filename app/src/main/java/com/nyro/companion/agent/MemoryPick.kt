package com.nyro.companion.agent

/**
 * Chooses which local memories go into the prompt. Sending all of them on every request cost about 450 tokens
 * for facts that rarely mattered; the ones that share words with the request are the useful ones, and the
 * newest fill the rest of the budget.
 */
object MemoryPick {
    private val words = Regex("[^\\p{L}\\p{N}]+")

    fun pick(memories: List<String>, request: String, limit: Int = 5): List<String> {
        if (memories.size <= limit) return memories
        val asking = request.lowercase().split(words).filter { it.length > 3 }.toSet()
        if (asking.isEmpty()) return memories.take(limit)
        // sortedByDescending is stable, so equally relevant memories keep their newest-first order and the
        // remaining slots are filled by the most recent ones instead of being left empty.
        return memories
            .map { it to score(it, asking) }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    private fun score(memory: String, asking: Set<String>): Int {
        val text = memory.lowercase()
        var score = 0
        for (w in asking) if (text.contains(w)) score += 10
        // A preference or name is worth keeping in view even when the wording differs.
        if (Regex("(?i)\\b(prefer|always|never|my name|call me|favourite|favorite|allerg|language)\\b").containsMatchIn(text)) score += 3
        return score
    }
}
