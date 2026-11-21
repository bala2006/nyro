package com.nyro.companion

import com.nyro.companion.agent.MemoryPick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPickTest {

    @Test
    fun everythingIsKeptWhenItAlreadyFits() {
        val few = listOf("likes Telugu", "works at NeuroPath")
        assertEquals(few, MemoryPick.pick(few, "open youtube"))
    }

    @Test
    fun theMemoryThatSharesWordsWithTheRequestWins() {
        val memories = listOf(
            "Prefers dark mode",
            "Works at NeuroPath on the billing module",
            "Has a dog named Bruno",
            "Birthday is in March",
            "Always uses Telugu for voice replies",
            "Commutes by metro",
        )
        val picked = MemoryPick.pick(memories, "find the pending payment in NeuroPath", limit = 2)
        assertTrue(picked.first().contains("NeuroPath"))
        assertEquals(2, picked.size)
    }

    @Test
    fun aPreferenceStaysVisibleEvenWithoutSharedWords() {
        val memories = listOf(
            "Prefers short answers",
            "Lives in Hyderabad",
            "Has a dog named Bruno",
            "Birthday is in March",
            "Commutes by metro",
            "Drinks filter coffee",
        )
        val picked = MemoryPick.pick(memories, "what is the weather", limit = 1)
        assertEquals("Prefers short answers", picked.single())
    }

    @Test
    fun theLimitIsRespectedAndOrderIsStable() {
        val memories = (1..20).map { "fact number $it" }
        val picked = MemoryPick.pick(memories, "tell me a fact", limit = 5)
        assertEquals(5, picked.size)
        assertEquals(memories.take(5), picked)
    }
}
