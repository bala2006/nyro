package com.nyro.companion

import com.nyro.companion.exec.EventGate
import com.nyro.companion.exec.EventKind
import com.nyro.companion.exec.Freshness
import com.nyro.companion.exec.BatchGuard
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventGateTest {
    private val own = "com.nyro.companion"

    @Test fun returnsQuicklyWhenNothingHappens() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val since = gate.version
        val r = gate.awaitSettled(since, firstEventTimeoutMs = 450)
        assertFalse(r.sawEvent)
        assertTrue(r.quiet)
        // The old wait always paid >= 470 ms (120 + 350). Here the cost is the first-event timeout at most.
        assertTrue("took ${currentTime}ms", currentTime <= 450)
    }

    @Test fun settlesShortlyAfterTheLastEvent() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val since = gate.version
        launch { delay(80); gate.onEvent(EventKind.WINDOW_STATE, "com.app"); delay(60); gate.onEvent(EventKind.WINDOW_CONTENT, "com.app") }
        val r = gate.awaitSettled(since, firstEventTimeoutMs = 450, quietMs = 140, maxMs = 1500)
        assertTrue(r.sawEvent); assertTrue(r.quiet)
        assertEquals(80L + 60L + 140L, currentTime) // last event at 140 ms, quiet window ends at 280 ms
    }

    @Test fun animatedScreensAreCappedNotWaitedForever() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val since = gate.version
        val ticker = launch { while (true) { gate.onEvent(EventKind.WINDOW_CONTENT, "com.duolingo"); delay(50) } }
        val r = gate.awaitSettled(since, firstEventTimeoutMs = 450, quietMs = 140, maxMs = 1500)
        ticker.cancel()
        assertTrue(r.sawEvent); assertFalse("still animating must be reported", r.quiet)
        assertTrue("capped at maxMs, took ${currentTime}", currentTime in 1500..1600)
    }

    @Test fun ownOverlayEventsAreIgnored() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val since = gate.version
        launch { repeat(10) { gate.onEvent(EventKind.WINDOW_CONTENT, own); delay(30) } }
        val r = gate.awaitSettled(since, firstEventTimeoutMs = 450)
        assertFalse("NYRO's own status pill must not look like the user's UI changing", r.sawEvent)
        assertEquals(since, gate.version)
    }

    @Test fun windowEventsFromAnyPackageCount() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val since = gate.windowVersion
        gate.onEvent(EventKind.WINDOWS, null)
        assertTrue(gate.windowVersion > since)
    }

    @Test fun awaitChangeTimesOut() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        assertFalse(gate.awaitChange(gate.version, 700))
        assertEquals(700L, currentTime)
    }

    @Test fun awaitChangeWakesOnEvent() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val since = gate.version
        launch { delay(120); gate.onEvent(EventKind.SCROLL, "com.app") }
        assertTrue(gate.awaitChange(since, 1000))
        assertTrue(currentTime in 120..220)
    }

    @Test fun conditionIsCheckedImmediatelyAndOnEvents() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        var ready = false
        var checks = 0
        launch { delay(500); ready = true; gate.onEvent(EventKind.WINDOW_STATE, "com.app") }
        val ok = gate.awaitCondition(5000, pollMs = 1000) { checks++; ready }
        assertTrue(ok)
        // Woken by the event (~500 ms + debounce), not by the 1000 ms poll.
        assertTrue("took ${currentTime}ms", currentTime in 500..760)
        assertTrue("checks=$checks", checks in 2..5)
    }

    @Test fun conditionFallsBackToPollingWhenEventsAreMissing() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        var ready = false
        launch { delay(900); ready = true } // no event is ever sent
        assertTrue(gate.awaitCondition(5000, pollMs = 300) { ready })
        assertTrue("took ${currentTime}ms", currentTime in 900..1300)
    }

    @Test fun conditionTimesOut() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        val r = async { gate.awaitCondition(1000, pollMs = 250) { false } }
        assertFalse(r.await())
        assertTrue(currentTime in 1000..1200)
    }

    @Test fun quietWaitIsImmediateWhenTheUiIsAlreadyQuiet() = runTest {
        val gate = EventGate(TestClock(testScheduler), own)
        gate.awaitQuiet(120, 800)
        assertEquals(0L, currentTime)
    }

    @Test fun snapshotFreshness() {
        // No event since the snapshot began and recent: reusable.
        assertTrue(Freshness.isFresh(snapshotVersion = 7, capturedAt = 1000, liveVersion = 7, now = 1200, maxAgeMs = 250))
        // An event advanced the counter: invalidated, however recent.
        assertFalse(Freshness.isFresh(7, 1000, liveVersion = 8, now = 1001, maxAgeMs = 250))
        // Too old even without events (some apps emit none).
        assertFalse(Freshness.isFresh(7, 1000, 7, now = 1400, maxAgeMs = 250))
        // maxAge 0 always re-reads.
        assertFalse(Freshness.isFresh(7, 1000, 7, now = 1001, maxAgeMs = 0))
    }

    @Test fun batchGuardRefusesStaleUiActions() {
        val g = BatchGuard()
        assertTrue(g.admit(mutatesUi = false)) // reads are always fine
        assertTrue(g.admit(true))
        g.record(mutatesUi = true, ok = true)  // the first tap changed the screen
        assertTrue(g.admit(false))             // reading afterwards is fine
        assertFalse(g.admit(true))             // a second UI change would use ids from the old screen
        val g2 = BatchGuard()
        g2.record(mutatesUi = true, ok = false) // a failed action changed nothing
        assertTrue(g2.admit(true))
    }
}
