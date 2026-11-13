package com.nyro.companion

import com.nyro.companion.agent.ContextBudget
import com.nyro.companion.ai.Msg
import com.nyro.companion.exec.ImageHash
import com.nyro.companion.exec.ObsRenderer
import com.nyro.companion.exec.ScreenChange
import com.nyro.companion.exec.ScreenshotPolicy
import com.nyro.companion.perf.LatencyStats
import com.nyro.companion.perf.Clock
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.TaskTrace
import com.nyro.companion.perf.percentile
import com.nyro.companion.perf.summarize
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenModelTest {
    private val topics = listOf("greetings", "food", "travel", "family", "weather", "work", "school", "music", "sports", "health", "shopping", "time", "colors", "animals", "city", "nature", "house", "clothes", "money", "friends", "hobbies", "news", "tech", "art", "history", "science", "books", "cooking", "garden", "ocean")

    /** A representative busy screen: a lesson-path style list with labelled buttons, texts and repeated rows. */
    private fun busyScreen(counter: Int = 5, scroll: Int = 0) = obs(
        "com.duolingo",
        *(listOf(
            el(1, "Text", "Learn"), el(2, "Button", "SECTION 2, UNIT 1"), el(3, "Text", "Use the passive voice"),
            el(4, "Button", "Practice", viewId = "practice_button"), el(5, "Text", "$counter day streak"),
            el(6, "Text", "${1200 + counter} XP"),
        ) + (7..90).map { i -> el(i, if (i % 3 == 0) "Button" else "Text", "Topic ${topics[(i + scroll) % topics.size]}", desc = if (i % 5 == 0) "Level ${topics[(i + scroll + 3) % topics.size]}" else "", viewId = if (i % 4 == 0) "row_$i" else "") }).toTypedArray(),
        title = "Learn",
    )

    @Test fun layoutSignatureIgnoresTickingCountersAndSmallScrolls() {
        val a = busyScreen(counter = 5)
        val b = busyScreen(counter = 6) // streak and XP numbers changed
        assertEquals("counters must not look like navigation", a.layoutSignature, b.layoutSignature)
        assertTrue("but the content did change", a.contentSignature != b.contentSignature)
        assertEquals(ScreenChange.CONTENT, b.change(a))
        assertEquals(ScreenChange.NONE, a.change(busyScreen(counter = 5)))
    }

    @Test fun aDifferentScreenHasADifferentLayout() {
        val path = busyScreen()
        val lesson = obs("com.duolingo", el(1, "Text", "Translate this sentence"), el(2, "Button", "bi"), el(3, "Button", "Check"))
        assertEquals(ScreenChange.LAYOUT, lesson.change(path))
        assertEquals(ScreenChange.LAYOUT, obs("com.other", *path.elements.toTypedArray()).change(path))
        assertEquals(ScreenChange.LAYOUT, path.change(null))
    }

    @Test fun scrollingToNewRowsChangesTheLayout() {
        assertEquals(ScreenChange.LAYOUT, busyScreen(scroll = 40).change(busyScreen(scroll = 0)))
    }

    @Test fun compactObservationsAreMuchSmallerThanTheOldFormat() {
        val s = busyScreen()
        val old = ObsRenderer.full(s, 90).length   // what every UI action used to return (max 90 elements, coordinates, ids)
        val compact = ObsRenderer.compact(s, 60).length
        val unchanged = ObsRenderer.unchanged(s).length
        println("observation size: old=$old chars (~${old / 4} tokens), compact=$compact (~${compact / 4}), unchanged=$unchanged (~${unchanged / 4})")
        assertTrue("compact $compact vs old $old", compact < old * 0.75)
        assertTrue(unchanged < 200)
    }

    @Test fun compactKeepsTheInformationTheModelNeeds() {
        val text = ObsRenderer.compact(busyScreen(), 60)
        assertTrue(text.contains("[2] Button \"SECTION 2, UNIT 1\" {tap}"))
        assertTrue(text.contains("Practice"))
        assertFalse("coordinates are for read_screen only", text.contains("@"))
        assertTrue(text.contains("more or repeated"))
    }

    @Test fun contextCompactionReplacesTheMiddleAndKeepsToolPairs() {
        val msgs = ArrayList<JsonObject>()
        msgs += Msg.system("sys"); msgs += Msg.user("task")
        repeat(30) { i ->
            msgs += Msg.assistantToolCalls("", listOf(com.nyro.companion.ai.ToolCall("c$i", "read_screen", "{}")))
            msgs += Msg.tool("c$i", "x".repeat(6000))
        }
        val before = ContextBudget.estimateTokens(msgs)
        assertTrue(ContextBudget.compact(msgs, "step log", compactAt = 1000, keepTail = 12, maxSummaryChars = 6000))
        val after = ContextBudget.estimateTokens(msgs)
        println("compaction: $before -> $after estimated tokens, ${msgs.size} messages")
        assertTrue(after < before / 2)
        assertEquals("sys", (msgs[0]["content"] as kotlinx.serialization.json.JsonPrimitive).content)
        // The first kept message after the summary must not be an orphan tool result.
        assertTrue(msgs[3]["role"].toString() != "\"tool\"")
        assertTrue(msgs[2]["content"].toString().contains("step log"))
    }

    @Test fun compactionDoesNothingBelowTheBudget() {
        val msgs = arrayListOf(Msg.system("s"), Msg.user("u"), Msg.assistant("a"))
        assertFalse(ContextBudget.compact(msgs, "", compactAt = 1000, keepTail = 12, maxSummaryChars = 100))
        assertEquals(3, msgs.size)
    }

    @Test fun imagesCountInTheEstimate() {
        val withImage = ContextBudget.estimateTokens(listOf(Msg.userWithImage("look", "AAAA")))
        assertTrue(withImage >= 1000)
    }

    @Test fun noAutomaticScreenshotWhenTheTreeIsEnough() {
        fun input(elements: Int = 40, interactive: Int = 12, shot: Boolean = false, allowed: Boolean = true, a11y: Boolean = true) =
            ScreenshotPolicy.Input(allowed, a11y, elements, interactive, shot)
        assertFalse("a normal screen needs no picture", ScreenshotPolicy.shouldAttachAutomatically(input()))
        assertTrue("an empty tree (canvas, game, video) falls back to a picture", ScreenshotPolicy.shouldAttachAutomatically(input(elements = 0, interactive = 0)))
        assertTrue("text only, nothing tappable", ScreenshotPolicy.shouldAttachAutomatically(input(elements = 8, interactive = 0)))
        assertFalse("never when the user disabled screenshots", ScreenshotPolicy.shouldAttachAutomatically(input(elements = 0, interactive = 0, allowed = false)))
        assertFalse("never twice in one batch", ScreenshotPolicy.shouldAttachAutomatically(input(elements = 0, interactive = 0, shot = true)))
        assertFalse(ScreenshotPolicy.shouldAttachAutomatically(input(elements = 0, interactive = 0, a11y = false)))
    }

    @Test fun identicalPicturesAreRecognised() {
        val a = IntArray(64) { if (it % 2 == 0) 200 else 40 }
        val noisy = a.copyOf().also { it[3] = 50; it[10] = 190 }
        val other = IntArray(64) { if (it < 32) 200 else 40 }
        assertTrue(ImageHash.distance(ImageHash.average(a), ImageHash.average(noisy)) <= ImageHash.SAME_THRESHOLD)
        assertTrue(ImageHash.distance(ImageHash.average(a), ImageHash.average(other)) > ImageHash.SAME_THRESHOLD)
    }

    @Test fun percentilesUseNearestRank() {
        val v = LongArray(100) { (it + 1).toLong() }
        assertEquals(50, percentile(v, 50)); assertEquals(95, percentile(v, 95)); assertEquals(99, percentile(v, 99))
        val s = summarize(longArrayOf(10, 20, 30, 40, 1000))
        assertEquals(30, s.p50); assertEquals(1000, s.p99); assertEquals(5, s.n); assertEquals(220, s.mean)
        assertEquals(0, summarize(LongArray(0)).n)
    }

    @Test fun statsKeepOnlyTheNewestSamplesAndRoundTrip() {
        val st = LatencyStats(capacity = 3)
        (1..5).forEach { st.record("tool", it * 10L) }
        assertEquals(3, st.summary("tool").n); assertEquals(40, st.summary("tool").p50) // 30, 40, 50 remain
        val copy = LatencyStats(3).also { it.decode(st.encode()) }
        assertEquals(st.summary("tool"), copy.summary("tool"))
    }

    @Test fun taskTraceSummarisesCallsTokensAndStages() {
        var now = 0L
        val t = TaskTrace("x", Clock { now })
        t.add(Metric.TTFT, 600); t.add(Metric.TTFT, 800); t.add(Metric.SNAPSHOT, 120)
        t.count(Metric.AZURE_CALLS, 2); t.count(Metric.TOKENS_IN, 5000); t.count(Metric.TOKENS_CACHED, 3000); t.count(Metric.TOKENS_NEW, 2000); t.count(Metric.TOKENS_OUT, 80)
        now = 4200
        val text = t.summaryText()
        assertTrue(text, text.startsWith("perf: total 4.2s · azure calls 2 · tokens in 5000 (cached 3000, new 2000) out 80"))
        assertTrue(text, text.contains("ttft: 2× total 1.4s avg 700ms max 800ms"))
        assertEquals(1400, t.total(Metric.TTFT)); assertEquals(2, t.calls(Metric.TTFT))
    }
}
