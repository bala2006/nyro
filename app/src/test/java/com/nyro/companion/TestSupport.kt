package com.nyro.companion

import com.nyro.companion.exec.ActionOutcome
import com.nyro.companion.exec.ActionPort
import com.nyro.companion.exec.AppResolver
import com.nyro.companion.exec.Obs
import com.nyro.companion.exec.ObsElement
import com.nyro.companion.exec.PlanAction
import com.nyro.companion.exec.PlanStep
import com.nyro.companion.exec.ScreenPort
import com.nyro.companion.perf.Clock
import com.nyro.companion.tools.Risk
import com.nyro.companion.tools.SensitiveUi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestCoroutineScheduler

/** Virtual-time clock: reads the test scheduler, so `delay()` in code under test advances it. */
class TestClock(private val scheduler: TestCoroutineScheduler) : Clock {
    override fun now(): Long = scheduler.currentTime
}

fun el(
    id: Int, role: String, text: String = "", desc: String = "", viewId: String = "",
    clickable: Boolean = role == "Button" || role == "Item", editable: Boolean = false, scrollable: Boolean = false, enabled: Boolean = true,
) = ObsElement(id = id, role = role, text = text, desc = desc, viewId = viewId, clickable = clickable, editable = editable, scrollable = scrollable, enabled = enabled,
    left = 0, top = id * 100, right = 500, bottom = id * 100 + 80)

fun obs(pkg: String, vararg elements: ObsElement, title: String = "", at: Long = 0, version: Long = 0) =
    Obs(packageName = pkg, appLabel = pkg.substringAfterLast('.'), title = title, elements = elements.toList(), capturedAt = at, eventVersion = version)

val testApps = AppResolver { name ->
    when (name.lowercase()) {
        "duolingo", "com.duolingo" -> "com.duolingo"
        "youtube", "com.google.android.youtube" -> "com.google.android.youtube"
        "shop", "com.shop" -> "com.shop"
        else -> null
    }
}

/**
 * A tiny simulated phone: named screens, tap transitions by label, a back stack, optional "ignores accessibility click"
 * behaviour, and the real [SensitiveUi] risk rules. It plays both the [ScreenPort] and [ActionPort] roles, driven by
 * virtual time, so executor tests run without a device.
 */
class FakeWorld(private val clock: Clock, start: String, private val screens: Map<String, FakeScreen>) : ScreenPort, ActionPort {
    data class FakeScreen(
        val pkg: String,
        val elements: List<ObsElement>,
        /** label (lower case) -> screen id */
        val taps: Map<String, String> = emptyMap(),
        /** Labels whose accessibility click does nothing; only a real gesture works. */
        val needsGesture: Set<String> = emptySet(),
        /** Screens that appear only after a delay (virtual ms) following a tap. */
        val delayMs: Long = 0,
        /** Labels shown after scrolling down (swapped in on scroll). */
        val afterScroll: List<ObsElement>? = null,
    )

    var current: String = start
    val stack = ArrayDeque<String>()
    val performed = mutableListOf<String>()
    val tappedIds = mutableListOf<Int?>()
    val methods = mutableListOf<String?>()
    var observeCalls = 0
    var declineLabels: Set<String> = emptySet()
    private var pendingScreen: String? = null
    private var pendingAt = 0L
    private var scrolled = false

    private fun settle() {
        val p = pendingScreen
        if (p != null && clock.now() >= pendingAt) { current = p; pendingScreen = null }
    }

    private fun screen() = screens.getValue(current)

    private fun currentObs(): Obs {
        settle()
        val s = screen()
        val els = if (scrolled && s.afterScroll != null) s.afterScroll else s.elements
        return Obs(s.pkg, s.pkg.substringAfterLast('.'), "", els, capturedAt = clock.now(), eventVersion = 0)
    }

    override suspend fun observe(maxAgeMs: Long): Obs { observeCalls++; return currentObs() }

    override suspend fun awaitObs(timeoutMs: Long, check: (Obs) -> Boolean): Obs? {
        val deadline = clock.now() + timeoutMs
        while (true) {
            val o = currentObs()
            if (check(o)) return o
            if (clock.now() >= deadline) return null
            delay(100)
        }
    }

    override suspend fun perform(step: PlanStep, elementId: Int?, method: String?): ActionOutcome {
        settle()
        methods += method
        when (step.action) {
            PlanAction.TAP, PlanAction.LONG_PRESS -> {
                val e = currentObs().byId(elementId) ?: return ActionOutcome(false, "no such element")
                val label = e.label
                tappedIds += elementId
                performed += "tap:$label"
                val assessment = SensitiveUi.classify(label, screen().pkg)
                val risk = assessment?.risk ?: Risk.LOW
                if (risk != Risk.LOW && label.lowercase() in declineLabels) return ActionOutcome(false, "DENIED: user declined", denied = true, risk = risk)
                val ignored = label.lowercase() in screen().needsGesture && method != "gesture"
                val next = screen().taps[label.lowercase()]
                if (next != null && !ignored) {
                    stack.addLast(current)
                    if (screen().delayMs > 0) { pendingScreen = next; pendingAt = clock.now() + screen().delayMs } else current = next
                    scrolled = false
                }
                return ActionOutcome(true, "tapped $label", risk = risk, obs = if (screen().delayMs > 0 && pendingScreen != null) currentObs() else currentObs())
            }
            PlanAction.PRESS_BACK -> { performed += "back"; current = stack.removeLastOrNull() ?: current; scrolled = false; return ActionOutcome(true, "back", obs = currentObs()) }
            PlanAction.OPEN_APP -> {
                performed += "open:${step.app}"
                val target = screens.entries.firstOrNull { testApps.packageFor(step.app.orEmpty()) == it.value.pkg && it.key.endsWith("_home") }?.key
                    ?: return ActionOutcome(false, "no such app")
                stack.addLast(current); current = target
                return ActionOutcome(true, "opened", obs = currentObs())
            }
            PlanAction.SCROLL -> {
                performed += "scroll"
                if (screen().afterScroll != null) scrolled = true // a second scroll finds the end of the list: nothing changes
                return ActionOutcome(true, "scrolled", obs = currentObs())
            }
            PlanAction.TYPE_TEXT -> { performed += "type:${step.text}"; return ActionOutcome(true, "typed", obs = currentObs()) }
            else -> { performed += step.action.wire; return ActionOutcome(true, "ok", obs = currentObs()) }
        }
    }
}
