package com.nyro.companion.exec

/** When a cached snapshot may still be used. Pure so the rule is testable. */
object Freshness {
    /**
     * A snapshot is reusable only if no relevant accessibility event happened since it started ([snapshotVersion] equals the
     * live counter) and it is not older than [maxAgeMs]. Events from NYRO's own overlay never advance the counter.
     */
    fun isFresh(snapshotVersion: Long, capturedAt: Long, liveVersion: Long, now: Long, maxAgeMs: Long): Boolean =
        snapshotVersion == liveVersion && now - capturedAt <= maxAgeMs
}

/**
 * One model turn may contain several tool calls. Read-only calls may all run. Once a call has changed the UI, any later
 * UI-changing call in the same turn is refused, because its target was chosen on a screen that no longer exists.
 */
class BatchGuard {
    private var uiChanged = false

    fun admit(mutatesUi: Boolean): Boolean = !(mutatesUi && uiChanged)

    fun record(mutatesUi: Boolean, ok: Boolean) { if (mutatesUi && ok) uiChanged = true }
}
