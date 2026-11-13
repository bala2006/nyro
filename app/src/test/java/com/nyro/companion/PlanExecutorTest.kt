package com.nyro.companion

import com.nyro.companion.FakeWorld.FakeScreen
import com.nyro.companion.exec.ActionOutcome
import com.nyro.companion.exec.ActionPort
import com.nyro.companion.exec.Condition
import com.nyro.companion.exec.HandoffReason
import com.nyro.companion.exec.Plan
import com.nyro.companion.exec.PlanAction
import com.nyro.companion.exec.PlanExecutor
import com.nyro.companion.exec.PlanOutcome
import com.nyro.companion.exec.PlanParser
import com.nyro.companion.exec.PlanReport
import com.nyro.companion.exec.PlanStep
import com.nyro.companion.exec.ProgressTracker
import com.nyro.companion.exec.ObsRenderer
import com.nyro.companion.exec.Selector
import com.nyro.companion.tools.Risk
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanExecutorTest {
    private val DUO = "com.duolingo"

    /** Duolingo-like world: home path -> section overview (tap "start" -> lesson) ; "SECTION 2, UNIT 1" opens an overview. */
    private fun duolingoScreens() = mapOf(
        "launcher" to FakeScreen("com.launcher", listOf(el(1, "Item", "Duolingo"))),
        "duo_home" to FakeScreen(DUO, listOf(el(1, "Item", "SECTION 2, UNIT 1"), el(2, "Button", "Practice"), el(3, "Text", "Learn")),
            taps = mapOf("section 2, unit 1" to "overview", "practice" to "lesson")),
        "overview" to FakeScreen(DUO, listOf(el(1, "Text", "Unit guide"), el(2, "Button", "Start"), el(3, "Button", "Close")),
            taps = mapOf("start" to "lesson", "close" to "duo_home")),
        "lesson" to FakeScreen(DUO, listOf(el(1, "Text", "Translate this sentence"), el(2, "Button", "bi"), el(3, "Button", "Check"))),
    )

    private fun TestScope.world(start: String = "duo_home", screens: Map<String, FakeScreen> = duolingoScreens()) = FakeWorld(TestClock(testScheduler), start, screens)

    private fun TestScope.executor(w: FakeWorld, tracker: ProgressTracker = ProgressTracker(TestClock(testScheduler))) =
        PlanExecutor(w, w, tracker, TestClock(testScheduler), testApps)

    private fun plan(json: String): Plan = PlanParser.parse(Json.parseToJsonElement(json).jsonObject).also { assertTrue(it.errors.toString(), it.errors.isEmpty()) }.plan!!

    @Test fun runsSeveralStepsWithoutAskingTheModelBetweenThem() = runTest {
        val w = world()
        val outcome = executor(w).run(plan("""{"goal":"start lesson","steps":[
          {"do":"tap","target":{"label":"SECTION 2, UNIT 1","match":"starts"},"expect":[{"visible":{"label":"Unit guide"}}]},
          {"do":"tap","target":{"label":"Start","role":"Button"},"expect":[{"visible":{"label":"Translate"}}]}]}"""))
        assertTrue(outcome.toString(), outcome is PlanOutcome.Completed)
        assertEquals(listOf("tap:SECTION 2, UNIT 1", "tap:Start"), w.performed)
        assertEquals("lesson", w.current)
    }

    @Test fun waitsForAConditionInsteadOfSleeping() = runTest {
        val screens = duolingoScreens().toMutableMap()
        screens["duo_home"] = screens.getValue("duo_home").copy(delayMs = 700) // the new screen appears 700 ms after the tap
        val w = world(screens = screens)
        val outcome = executor(w).run(plan("""{"steps":[
          {"do":"tap","target":{"label":"Practice"},"expect":[{"visible":{"label":"Translate"}}],"timeout_ms":3000}]}"""))
        assertTrue(outcome.toString(), outcome is PlanOutcome.Completed)
        assertTrue("took ${testScheduler.currentTime}ms", testScheduler.currentTime in 700..900)
    }

    @Test fun anUnexpectedScreenReturnsControlToTheModel() = runTest {
        val w = world()
        val outcome = executor(w).run(plan("""{"steps":[
          {"do":"tap","target":{"label":"SECTION 2, UNIT 1","match":"starts"},"expect":[{"visible":{"label":"Translate"}}],"timeout_ms":600},
          {"do":"tap","target":{"label":"Start"}}]}""")) // second step must NOT run
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.EXPECTATION, outcome.reason)
        assertEquals(0, outcome.stepIndex)
        assertEquals(listOf("tap:SECTION 2, UNIT 1"), w.performed)
        assertTrue(outcome.obs!!.elements.any { it.text == "Start" }) // the model gets the screen that is actually showing
        val report = PlanReport.format(plan("""{"steps":[{"do":"tap","target":{"label":"x"}}]}"""), outcome) { ObsRenderer.compact(it) }
        assertTrue(report, report.startsWith("PLAN STOPPED at step 1/1 [expectation]"))
    }

    @Test fun staleSequencesAreNotRun() = runTest {
        // The target of step 2 exists only on the screen step 1 was supposed to open. Step 1 did something else.
        val w = world()
        val outcome = executor(w).run(plan("""{"steps":[
          {"do":"tap","target":{"label":"Practice"},"expect":[{"visible":{"label":"Unit guide"}}],"timeout_ms":500},
          {"do":"tap","target":{"label":"Start"}}]}"""))
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.EXPECTATION, outcome.reason)
        assertEquals(listOf("tap:Practice"), w.performed)
    }

    @Test fun missingTargetsAreReportedNotGuessed() = runTest {
        val w = world()
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Nonexistent"}}]}"""))
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.NOT_FOUND, outcome.reason)
        assertTrue(w.performed.isEmpty())
    }

    @Test fun ambiguousTargetsAreReportedNotGuessed() = runTest {
        val screens = mapOf("s" to FakeScreen("com.app", listOf(el(1, "Button", "Follow"), el(2, "Button", "Follow"), el(3, "Button", "Follow"))))
        val w = world("s", screens)
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Follow","match":"exact"}}]}"""))
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.AMBIGUOUS, outcome.reason)
        assertTrue(w.performed.isEmpty())
        // With an index it is unambiguous.
        val ok = executor(world("s", screens)).run(plan("""{"steps":[{"do":"tap","target":{"label":"Follow","match":"exact","index":2},"expect":[{"changed":true}]}]}"""))
        assertTrue(ok is PlanOutcome.Handoff) // tapped, but nothing changes in this static fake: still reported honestly
    }

    @Test fun scrollsToFindATargetWithinALimit() = runTest {
        val screens = mapOf("s" to FakeScreen("com.app", listOf(el(1, "Text", "Top")), afterScroll = listOf(el(1, "Text", "Top"), el(2, "Button", "Load more"))))
        val w = world("s", screens)
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Load more"},"scroll_to_find":"down","expect":[{"changed":true}]}]}"""))
        assertEquals(listOf("scroll", "tap:Load more"), w.performed.take(2))
        assertTrue(outcome is PlanOutcome.Handoff || outcome is PlanOutcome.Completed)
        // A list with no such element ends after the first scroll that changes nothing.
        val w2 = world("s", mapOf("s" to FakeScreen("com.app", listOf(el(1, "Text", "Top")))))
        val o2 = executor(w2).run(plan("""{"steps":[{"do":"tap","target":{"label":"Nope"},"scroll_to_find":"down"}]}"""))
        o2 as PlanOutcome.Handoff
        assertEquals(HandoffReason.NOT_FOUND, o2.reason)
        assertTrue(w2.performed.size <= 1)
    }

    @Test fun recoversOnceWhenAnAccessibilityClickIsIgnored() = runTest {
        val screens = duolingoScreens().toMutableMap()
        screens["overview"] = screens.getValue("overview").copy(needsGesture = setOf("start"))
        val w = world("overview", screens)
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Start"},"expect":[{"visible":{"label":"Translate"}}]}]}"""))
        assertTrue(outcome.toString(), outcome is PlanOutcome.Completed)
        assertEquals(listOf<String?>(null, "gesture"), w.methods) // first the normal click, then exactly one retry as a real touch
        assertEquals("lesson", w.current)
    }

    @Test fun recoveryIsBounded() = runTest {
        val screens = duolingoScreens().toMutableMap()
        screens["overview"] = screens.getValue("overview").copy(taps = emptyMap()) // nothing the app does will respond
        val w = world("overview", screens)
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Start"},"expect":[{"visible":{"label":"Translate"}}],"timeout_ms":500}]}"""))
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.EXPECTATION, outcome.reason)
        assertEquals(2, w.performed.size) // original + one retry, never more
    }

    @Test fun theDuolingoNavigationLoopIsBlocked() = runTest {
        // tap section -> overview; back -> path; tap the same section again: the tracker must refuse the repeat.
        val w = world()
        val tracker = ProgressTracker(TestClock(testScheduler))
        val ex = executor(w, tracker)
        ex.run(plan("""{"steps":[{"do":"tap","target":{"label":"SECTION 2, UNIT 1","match":"starts"},"expect":[{"visible":{"label":"Unit guide"}}]},{"do":"press_back","expect":[{"visible":{"label":"Learn"}}]}]}"""))
        assertEquals("duo_home", w.current)
        val again = ex.run(plan("""{"steps":[{"do":"tap","target":{"label":"SECTION 2, UNIT 1","match":"starts"},"expect":[{"visible":{"label":"Unit guide"}}]}]}"""))
        again as PlanOutcome.Handoff
        assertEquals(HandoffReason.LOOP, again.reason)
        assertTrue(again.detail, again.detail.contains("already opened"))
        assertEquals("the second tap must never run", 1, w.performed.count { it == "tap:SECTION 2, UNIT 1" })
        assertEquals(1, w.performed.count { it == "back" })
    }

    @Test fun anAlreadyOpenAppIsNotReopened() = runTest {
        val w = world()
        val outcome = executor(w).run(plan("""{"steps":[{"do":"open_app","app":"Duolingo","expect":[{"app":"Duolingo"}]},{"do":"tap","target":{"label":"Practice"},"expect":[{"visible":{"label":"Translate"}}]}]}"""))
        assertTrue(outcome.toString(), outcome is PlanOutcome.Completed)
        assertFalse(w.performed.any { it.startsWith("open:") })
        assertEquals("already in front", (outcome as PlanOutcome.Completed).done[0].note)
    }

    @Test fun preconditionsAreCheckedBeforeActing() = runTest {
        val w = world()
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Practice"},"require":[{"visible":{"label":"Checkout"}}],"timeout_ms":500}]}"""))
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.PRECONDITION, outcome.reason)
        assertTrue(w.performed.isEmpty())
    }

    @Test fun userDeclineStopsEverything() = runTest {
        val screens = mapOf(
            "cart" to FakeScreen("com.shop", listOf(el(1, "Button", "Pay now"), el(2, "Button", "Continue shopping")), taps = mapOf("pay now" to "done")),
            "done" to FakeScreen("com.shop", listOf(el(1, "Text", "Order placed"))),
        )
        val w = world("cart", screens).also { it.declineLabels = setOf("pay now") }
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Pay now"},"expect":[{"visible":{"label":"Order placed"}}]},{"do":"tap","target":{"label":"Continue shopping"}}]}"""))
        assertTrue(outcome.toString(), outcome is PlanOutcome.Denied)
        assertEquals("cart", w.current)
        assertEquals(listOf("tap:Pay now"), w.performed) // nothing after the declined step
    }

    @Test fun sensitiveStepsEndThePlanSoTheModelSeesTheResult() = runTest {
        val screens = mapOf(
            "chat" to FakeScreen("com.chat", listOf(el(1, "Input", "Message", editable = true), el(2, "Button", "Send"), el(3, "Button", "Archive")),
                taps = mapOf("send" to "sent", "archive" to "sent")),
            "sent" to FakeScreen("com.chat", listOf(el(1, "Text", "Delivered"), el(2, "Button", "Archive"))),
        )
        val w = world("chat", screens)
        val outcome = executor(w).run(plan("""{"steps":[
          {"do":"tap","target":{"label":"Send"},"expect":[{"visible":{"label":"Delivered"}}]},
          {"do":"tap","target":{"label":"Archive"}}]}"""))
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.CHECKPOINT, outcome.reason) // "Send" is medium risk: the follow-up tap is not run blindly
        assertEquals(listOf("tap:Send"), w.performed)
    }

    @Test fun cancellationStopsBetweenStepsAndMidWait() = runTest {
        val w = world()
        val job = launch {
            executor(w).run(plan("""{"steps":[
              {"do":"tap","target":{"label":"SECTION 2, UNIT 1","match":"starts"},"expect":[{"visible":{"label":"Never appears"}}],"timeout_ms":15000},
              {"do":"tap","target":{"label":"Start"}}]}"""))
        }
        delay(1000)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, w.performed.size) // the second step never started
    }

    @Test fun aPlanThatRunsTooLongStops() = runTest {
        val actions = object : ActionPort {
            override suspend fun perform(step: PlanStep, elementId: Int?, method: String?): ActionOutcome { delay(30_000); return ActionOutcome(true, obs = obs("com.a", el(1, "Button", "x${testScheduler.currentTime}"))) }
        }
        val w = world("s", mapOf("s" to FakeScreen("com.a", listOf(el(1, "Button", "go")))))
        val steps = (1..5).joinToString(",") { """{"do":"scroll","direction":"down","expect":[]}""" }
        val ex = PlanExecutor(w, actions, ProgressTracker(TestClock(testScheduler)), TestClock(testScheduler), testApps)
        val outcome = ex.run(plan("""{"steps":[$steps]}"""))
        // 30 s per step: after 3 steps the 90 s plan budget is spent.
        outcome as PlanOutcome.Handoff
        assertEquals(HandoffReason.TIMEOUT, outcome.reason)
    }

    @Test fun planStepsGoThroughTheActionPortSoSafetyAppliesToEach() = runTest {
        val seen = mutableListOf<Pair<PlanAction, Int?>>()
        val port = object : ActionPort {
            override suspend fun perform(step: PlanStep, elementId: Int?, method: String?): ActionOutcome {
                seen += step.action to elementId
                return ActionOutcome(true, risk = Risk.LOW, obs = obs("com.app", el(1, "Button", "after ${seen.size}")))
            }
        }
        val w = world("s", mapOf("s" to FakeScreen("com.app", listOf(el(7, "Button", "Go")))))
        PlanExecutor(w, port, ProgressTracker(TestClock(testScheduler)), TestClock(testScheduler), testApps)
            .run(plan("""{"steps":[{"do":"tap","target":{"label":"Go"}}]}"""))
        // The executor passes the element id it resolved on the live screen; the port (Agent) assesses risk and asks the user.
        assertEquals(listOf(PlanAction.TAP to 7), seen)
    }

    @Test fun selectorsResolveOnRoleAndText() = runTest {
        val screens = mapOf(
            "s" to FakeScreen("com.app", listOf(el(1, "Text", "Search"), el(2, "Button", "Search"), el(3, "Input", "Search here", editable = true)), taps = mapOf("search" to "results")),
            "results" to FakeScreen("com.app", listOf(el(1, "Text", "Results"))),
        )
        val w = world("s", screens)
        val outcome = executor(w).run(plan("""{"steps":[{"do":"tap","target":{"label":"Search","role":"Button"},"expect":[{"visible":{"label":"Results"}}]}]}"""))
        assertTrue(outcome.toString(), outcome is PlanOutcome.Completed)
        assertEquals("the Button (id 2), not the heading text or the input", listOf<Int?>(2), w.tappedIds)
    }

    @Test fun conditionHelpersDescribeThemselves() {
        assertEquals("app is Duolingo", Condition.App("Duolingo").describe())
        assertEquals("\"Start\" visible", Condition.Visible(Selector(label = "Start")).describe())
    }
}
