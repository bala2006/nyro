package com.nyro.companion.exec

import com.nyro.companion.perf.Clock
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

enum class EventKind { WINDOW_STATE, WINDOW_CONTENT, WINDOWS, SCROLL, FOCUS, OTHER }

/**
 * @property sawEvent false when no UI event arrived within the first-event timeout (the action likely had no visible effect).
 * @property quiet false when the UI never went quiet before the cap (an animated screen), so "settled" is a best effort.
 */
data class SettleResult(val sawEvent: Boolean, val quiet: Boolean, val elapsedMs: Long)

/**
 * Turns accessibility events into waits. The service calls [onEvent] on its main thread; the agent waits on a
 * coroutine. Waits are bounded and verified by the caller against a real snapshot, because accessibility events
 * can be noisy, throttled or missing.
 *
 * Why not "no events for 350 ms" as before: animated apps never go quiet, NYRO's own overlay produces events, and
 * the old wait always paid a fixed delay even when nothing happened. Here the wait ends as soon as (a) no event shows
 * up at all, or (b) an event showed up and the UI then stayed quiet for [quietMs].
 */
class EventGate(private val clock: Clock, private val ownPackage: String = "") {
    @Volatile var version: Long = 0L
        private set
    @Volatile var windowVersion: Long = 0L
        private set
    @Volatile var lastEventAt: Long = Long.MIN_VALUE / 2
        private set

    private val signal = Channel<Unit>(Channel.CONFLATED)

    fun onEvent(kind: EventKind, pkg: String?) {
        // NYRO's own overlay (status pill, focus ring) is not the user's UI.
        if (!pkg.isNullOrEmpty() && pkg == ownPackage && kind != EventKind.WINDOWS) return
        version++
        if (kind == EventKind.WINDOW_STATE || kind == EventKind.WINDOWS) windowVersion++
        lastEventAt = clock.now()
        signal.trySend(Unit)
    }

    /** True once [version] differs from [since], false if [timeoutMs] passes first. */
    suspend fun awaitChange(since: Long, timeoutMs: Long): Boolean {
        if (version != since) return true
        val deadline = clock.now() + timeoutMs
        while (true) {
            val remaining = deadline - clock.now()
            if (remaining <= 0) return version != since
            withTimeoutOrNull(minOf(remaining, SLICE_MS)) { signal.receive() }
            if (version != since) return true
        }
    }

    /**
     * Waits for the UI to react to an action that was started when [version] was [since], then for it to go quiet.
     * Returns quickly when nothing happens, instead of always paying a fixed idle delay.
     */
    suspend fun awaitSettled(since: Long, firstEventTimeoutMs: Long = 450, quietMs: Long = 140, maxMs: Long = 1500): SettleResult {
        val start = clock.now()
        if (!awaitChange(since, firstEventTimeoutMs)) return SettleResult(sawEvent = false, quiet = true, elapsedMs = clock.now() - start)
        return quietPhase(start, quietMs, maxMs, sawEvent = true)
    }

    /** Waits until no event arrived for [quietMs] (immediate if the UI is already quiet), capped at [maxMs]. */
    suspend fun awaitQuiet(quietMs: Long = 120, maxMs: Long = 800): SettleResult =
        quietPhase(clock.now(), quietMs, maxMs, sawEvent = false)

    private suspend fun quietPhase(start: Long, quietMs: Long, maxMs: Long, sawEvent: Boolean): SettleResult {
        while (true) {
            val now = clock.now()
            val sinceLast = now - lastEventAt
            if (sinceLast >= quietMs) return SettleResult(sawEvent, true, now - start)
            val used = now - start
            if (used >= maxMs) return SettleResult(sawEvent, false, used)
            delay(minOf(quietMs - sinceLast, maxMs - used).coerceAtLeast(10))
        }
    }

    /**
     * Polls [check] when an event arrives (debounced) or every [pollMs] when none do, until it returns true or
     * [timeoutMs] passes. [minGapMs] keeps a constantly-animating screen from turning this into a busy loop.
     */
    suspend fun awaitCondition(
        timeoutMs: Long,
        pollMs: Long = 300,
        debounceMs: Long = 60,
        minGapMs: Long = 100,
        check: suspend () -> Boolean,
    ): Boolean {
        val deadline = clock.now() + timeoutMs
        var seen = version
        var lastCheckEnd = Long.MIN_VALUE / 2
        while (true) {
            val gap = minGapMs - (clock.now() - lastCheckEnd)
            if (gap > 0) delay(gap)
            seen = version
            if (check()) return true
            lastCheckEnd = clock.now()
            val remaining = deadline - lastCheckEnd
            if (remaining <= 0) return false
            if (version != seen) { delay(minOf(debounceMs, remaining)); continue }
            awaitChange(seen, minOf(pollMs, remaining))
            val left = deadline - clock.now()
            if (left <= 0) return check()
            delay(minOf(debounceMs, left))
        }
    }

    companion object { private const val SLICE_MS = 100L }
}
