package com.nyro.companion.tools

import com.nyro.companion.access.ScreenReader
import com.nyro.companion.exec.Obs
import com.nyro.companion.exec.ScreenPort
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf

/** [ScreenPort] backed by the accessibility service: cached snapshots invalidated by events, event-driven waits. */
object AndroidScreenPort : ScreenPort {
    private fun svc() = ScreenSession.svc() ?: throw IllegalStateException(Requirement.ACCESSIBILITY.label)

    override suspend fun observe(maxAgeMs: Long): Obs = ScreenSession.fresh(svc(), maxAgeMs).obs

    override suspend fun awaitObs(timeoutMs: Long, check: (Obs) -> Boolean): Obs? {
        val s = svc()
        var hit: Obs? = null
        Perf.time(Metric.UI_WAIT) {
            s.gate.awaitCondition(timeoutMs) {
                val o = ScreenSession.read(s, ScreenReader.OBSERVE_BUDGET_MS).obs
                if (check(o)) { hit = o; true } else false
            }
        }
        return hit
    }
}
