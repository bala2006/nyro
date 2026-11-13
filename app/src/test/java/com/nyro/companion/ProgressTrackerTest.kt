package com.nyro.companion

import com.nyro.companion.exec.ActionKey
import com.nyro.companion.exec.ActionKind
import com.nyro.companion.exec.ProgressTracker
import com.nyro.companion.exec.ScreenChange
import com.nyro.companion.exec.Verdict
import com.nyro.companion.perf.Clock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTrackerTest {
    private val tracker = ProgressTracker(Clock { 0L })
    private val section = ActionKey("tap_element", "section 2, unit 1")
    private val back = ActionKey("press_back", "")
    private val pathScreen = 111L
    private val overview = 222L
    private val lesson = 333L

    @Test fun tapBackTapIsBlockedAsInTheDuolingoTrace() {
        // 1. tap SECTION 2, UNIT 1 on the path -> opens the section overview
        assertEquals(Verdict.Proceed, tracker.check(section, ActionKind.TAP, pathScreen))
        tracker.record(section, ActionKind.TAP, pathScreen, overview, ScreenChange.LAYOUT, ok = true)
        // 2. go back -> path
        assertEquals(Verdict.Proceed, tracker.check(back, ActionKind.BACK, overview))
        tracker.record(back, ActionKind.BACK, overview, pathScreen, ScreenChange.LAYOUT, ok = true)
        // 3. tap the same section again: this is what the model did, and what must now be refused
        val v = tracker.check(section, ActionKind.TAP, pathScreen)
        assertTrue(v.toString(), v is Verdict.Block)
        assertEquals("bounce", (v as Verdict.Block).code)
        assertTrue(v.toModelText().startsWith("BLOCKED by NYRO"))
    }

    @Test fun differentTargetOnTheSameScreenIsFine() {
        tracker.record(section, ActionKind.TAP, pathScreen, overview, ScreenChange.LAYOUT, true)
        tracker.record(back, ActionKind.BACK, overview, pathScreen, ScreenChange.LAYOUT, true)
        assertEquals(Verdict.Proceed, tracker.check(ActionKey("tap_element", "practice"), ActionKind.TAP, pathScreen))
    }

    @Test fun sameTapOnAnotherScreenIsFine() {
        tracker.record(section, ActionKind.TAP, pathScreen, overview, ScreenChange.LAYOUT, true)
        assertEquals(Verdict.Proceed, tracker.check(section, ActionKind.TAP, lesson))
    }

    @Test fun anActionThatChangedNothingIsNotRepeated() {
        val start = ActionKey("tap_text", "start")
        tracker.record(start, ActionKind.TAP, overview, overview, ScreenChange.NONE, ok = true) // "success" without progress
        val v = tracker.check(start, ActionKind.TAP, overview)
        assertEquals("no_effect", (v as Verdict.Block).code)
    }

    @Test fun aFailedActionIsNotRepeatedOnTheSameScreen() {
        tracker.record(section, ActionKind.TAP, pathScreen, pathScreen, ScreenChange.NONE, ok = false)
        assertTrue(tracker.check(section, ActionKind.TAP, pathScreen) is Verdict.Block)
    }

    @Test fun contentOnlyChangeCountsAsProgress() {
        val next = ActionKey("tap_text", "continue")
        tracker.record(next, ActionKind.TAP, lesson, lesson, ScreenChange.CONTENT, ok = true) // same layout, new question text
        assertEquals(Verdict.Proceed, tracker.check(next, ActionKind.TAP, lesson))
    }

    @Test fun sameActionThreeTimesIsStoppedEvenWhenItAlwaysChangesSomething() {
        val k = ActionKey("tap_text", "continue")
        repeat(2) { tracker.record(k, ActionKind.TAP, lesson, lesson, ScreenChange.CONTENT, true) }
        assertEquals("repeat", (tracker.check(k, ActionKind.TAP, lesson) as Verdict.Block).code)
    }

    @Test fun scrollingIsAllowedToRepeatButNotAtTheEndOfTheList() {
        val s = ActionKey("scroll", "down")
        repeat(3) { tracker.record(s, ActionKind.SCROLL, pathScreen, pathScreen, ScreenChange.CONTENT, true) }
        assertEquals(Verdict.Proceed, tracker.check(s, ActionKind.SCROLL, pathScreen))
        tracker.record(s, ActionKind.SCROLL, pathScreen, pathScreen, ScreenChange.NONE, true) // reached the end
        assertTrue(tracker.check(s, ActionKind.SCROLL, pathScreen) is Verdict.Block)
    }

    @Test fun reopeningAnAppThatChangedNothingIsBlocked() {
        val open = ActionKey("open_app", "com.google.android.youtube")
        assertEquals(Verdict.Proceed, tracker.check(open, ActionKind.OPEN_APP, 1L))
        tracker.record(open, ActionKind.OPEN_APP, 1L, 2L, ScreenChange.LAYOUT, true)
        tracker.record(open, ActionKind.OPEN_APP, 2L, 2L, ScreenChange.NONE, true) // already open: nothing changed
        assertTrue(tracker.check(open, ActionKind.OPEN_APP, 2L) is Verdict.Block)
    }

    @Test fun repeatedBlocksEndTheTaskInsteadOfLoopingForever() {
        val k = ActionKey("tap_text", "x")
        tracker.record(k, ActionKind.TAP, 5L, 5L, ScreenChange.NONE, true)
        assertTrue(tracker.check(k, ActionKind.TAP, 5L) is Verdict.Block)
        assertTrue(tracker.check(k, ActionKind.TAP, 5L) is Verdict.Block)
        assertTrue("the third refusal aborts the task", tracker.check(k, ActionKind.TAP, 5L) is Verdict.Abort)
    }

    @Test fun resetStartsAFreshTask() {
        tracker.record(section, ActionKind.TAP, pathScreen, pathScreen, ScreenChange.NONE, true)
        tracker.reset()
        assertEquals(Verdict.Proceed, tracker.check(section, ActionKind.TAP, pathScreen))
        assertEquals(0, tracker.blocks)
    }

    @Test fun summaryIsReadableInTheTaskLog() {
        tracker.record(section, ActionKind.TAP, pathScreen, overview, ScreenChange.LAYOUT, true)
        tracker.record(back, ActionKind.BACK, overview, pathScreen, ScreenChange.LAYOUT, true)
        assertEquals("tap_element(section 2, unit 1)>; press_back>", tracker.summary())
    }
}
