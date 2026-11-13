package com.nyro.companion.exec

import com.nyro.companion.perf.Clock
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf
import com.nyro.companion.tools.Risk
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** How the executor sees the screen. The Android implementation uses the accessibility service and an event gate. */
interface ScreenPort {
    /** A snapshot no older than [maxAgeMs] that no relevant accessibility event has invalidated. */
    suspend fun observe(maxAgeMs: Long = 250): Obs

    /** Event-driven wait until [check] holds for a fresh snapshot. Returns that snapshot, or null on timeout. */
    suspend fun awaitObs(timeoutMs: Long, check: (Obs) -> Boolean): Obs?
}

data class ActionOutcome(
    val ok: Boolean,
    val text: String = "",
    /** The user declined a confirmation. Nothing was done. */
    val denied: Boolean = false,
    val risk: Risk = Risk.LOW,
    /** Snapshot taken right after the action settled, if the action produced one. */
    val obs: Obs? = null,
)

/**
 * Runs one plan step through the normal tool pipeline (argument validation, permissions, risk assessment on the
 * real element, user confirmation, timeout). The plan engine never bypasses it.
 */
interface ActionPort {
    /** [method] = "gesture" asks tap-like actions to dispatch a real touch instead of an accessibility click. */
    suspend fun perform(step: PlanStep, elementId: Int?, method: String? = null): ActionOutcome
}

data class StepResult(val id: String, val label: String, val ok: Boolean, val ms: Long, val note: String = "")

enum class HandoffReason { PRECONDITION, NOT_FOUND, AMBIGUOUS, EXPECTATION, LOOP, ACTION_FAILED, TIMEOUT, CHECKPOINT, ABORTED }

sealed interface PlanOutcome {
    val done: List<StepResult>

    data class Completed(override val done: List<StepResult>, val finalObs: Obs?) : PlanOutcome

    /** Control returns to the model: ambiguity, a surprise, a failure, or a deliberate checkpoint. */
    data class Handoff(
        override val done: List<StepResult>,
        val reason: HandoffReason,
        val stepIndex: Int,
        val detail: String,
        val obs: Obs?,
    ) : PlanOutcome

    data class Denied(override val done: List<StepResult>, val stepIndex: Int, val detail: String) : PlanOutcome
}

/**
 * Plan once, execute locally. For each step: check preconditions, resolve the semantic target on a fresh snapshot,
 * ask the loop detector, act, wait for the UI to settle, verify the expectation, and recover once if the action was
 * delivered but nothing changed. Anything unexpected goes back to the model with a compact observation.
 */
class PlanExecutor(
    private val screen: ScreenPort,
    private val actions: ActionPort,
    private val tracker: ProgressTracker,
    private val clock: Clock,
    private val apps: AppResolver,
) {
    suspend fun run(plan: Plan): PlanOutcome {
        val done = ArrayList<StepResult>()
        val deadline = clock.now() + PlanLimits.MAX_PLAN_MS
        var obs = screen.observe()

        for ((i, step) in plan.steps.withIndex()) {
            currentCoroutineContext().ensureActive()
            val t0 = clock.now()
            fun handoff(reason: HandoffReason, detail: String, o: Obs? = obs) = PlanOutcome.Handoff(done.toList(), reason, i, detail, o)
            if (t0 > deadline) return handoff(HandoffReason.TIMEOUT, "The plan ran longer than ${PlanLimits.MAX_PLAN_MS / 1000}s.")

            // 1. Preconditions
            if (step.require.isNotEmpty() && !ConditionEvaluator.allMet(step.require, obs, obs, apps)) {
                val ready = screen.awaitObs(step.timeoutMs) { ConditionEvaluator.allMet(step.require, it, it, apps) }
                if (ready == null) {
                    val miss = ConditionEvaluator.firstUnmet(step.require, obs, obs, apps)
                    return handoff(HandoffReason.PRECONDITION, "Step ${i + 1} (${step.describe()}) needs: ${miss?.describe()}. That is not true on the current screen.", screen.observe(0))
                }
                obs = ready
            }

            // 2. wait_for has no action
            if (step.action == PlanAction.WAIT_FOR) {
                val before = obs
                val ok = Perf.time(Metric.VERIFY) { screen.awaitObs(step.timeoutMs) { ConditionEvaluator.allMet(step.expect, before, it, apps) } }
                if (ok == null) return handoff(HandoffReason.EXPECTATION, "Step ${i + 1} waited ${step.timeoutMs} ms for ${step.expect.joinToString(" and ") { it.describe() }}, but it did not happen.", screen.observe(0))
                obs = ok
                done += StepResult(step.id, step.describe(), true, clock.now() - t0)
                continue
            }

            // 3. Opening an app that is already in front is a no-op
            if (step.action == PlanAction.OPEN_APP && apps.packageFor(step.app.orEmpty()) == obs.packageName) {
                done += StepResult(step.id, step.describe(), true, clock.now() - t0, "already in front")
                continue
            }

            // 4. Resolve the target on a fresh snapshot (scrolling to find it if the step allows)
            var elementId: Int? = null
            val target = step.target
            if (target != null) {
                var scrolls = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val found = Perf.time(Metric.LOOKUP) { ElementMatcher.find(obs, target) }
                    when (found) {
                        is Match.Found -> { elementId = found.element.id; break }
                        is Match.Ambiguous -> return handoff(HandoffReason.AMBIGUOUS,
                            "Step ${i + 1}: ${target.describe()} matches ${found.candidates.size} elements (${found.candidates.joinToString { "\"${it.label.take(30)}\"" }}). Add an index or a more specific label.")
                        Match.NotFound -> {
                            val dir = step.scrollToFind
                            if (dir == null || scrolls >= PlanLimits.MAX_SCROLL_TO_FIND) {
                                if (step.action == PlanAction.TYPE_TEXT) break // typing falls back to the focused or first input
                                return handoff(HandoffReason.NOT_FOUND, "Step ${i + 1}: ${target.describe()} is not on screen${if (scrolls > 0) " after $scrolls scrolls" else ""}.")
                            }
                            val scroll = PlanStep(id = step.id + ".scroll", action = PlanAction.SCROLL, direction = dir)
                            val s = actions.perform(scroll, null)
                            if (s.denied) return PlanOutcome.Denied(done.toList(), i, "Declined: ${scroll.describe()}")
                            scrolls++
                            val before = obs
                            obs = s.obs ?: screen.observe(0)
                            if (obs.change(before) == ScreenChange.NONE) return handoff(HandoffReason.NOT_FOUND, "Step ${i + 1}: reached the end of the list without finding ${target.describe()}.")
                        }
                    }
                }
            }

            // 5. Loop detection
            val key = step.actionKey()
            val kind = kindOf(step.action)
            when (val v = tracker.check(key, kind, obs.layoutSignature)) {
                is Verdict.Block -> return handoff(HandoffReason.LOOP, v.toModelText())
                is Verdict.Abort -> return handoff(HandoffReason.ABORTED, v.reason)
                Verdict.Proceed -> Unit
            }

            // 6. Act
            val before = obs
            var out = actions.perform(step, elementId)
            if (out.denied) return PlanOutcome.Denied(done.toList(), i, "The user declined: ${step.describe()}.")
            if (!out.ok) {
                tracker.record(key, kind, before.layoutSignature, before.layoutSignature, ScreenChange.NONE, ok = false)
                return handoff(HandoffReason.ACTION_FAILED, "Step ${i + 1} (${step.describe()}) failed: ${out.text.lineSequence().firstOrNull().orEmpty().take(200)}", screen.observe(0))
            }

            // 7. Verify the expectation; the tool already waited for the UI, so check what it saw first
            val expects = PlanValidator.effectiveExpect(step)
            var after = out.obs ?: screen.observe(0)
            var met = Perf.time(Metric.VERIFY) { ConditionEvaluator.allMet(expects, before, after, apps) }
            if (!met) {
                val w = Perf.time(Metric.VERIFY) { screen.awaitObs(step.timeoutMs) { ConditionEvaluator.allMet(expects, before, it, apps) } }
                if (w != null) { after = w; met = true } else after = screen.observe(0)
            }

            // 8. One bounded recovery: the action reported success but nothing changed, so deliver a real touch instead
            if (!met && after.change(before) == ScreenChange.NONE && (step.action == PlanAction.TAP || step.action == PlanAction.LONG_PRESS)) {
                val retry = actions.perform(step, elementId, method = "gesture")
                if (retry.denied) return PlanOutcome.Denied(done.toList(), i, "The user declined: ${step.describe()}.")
                if (retry.ok) {
                    after = retry.obs ?: screen.observe(0)
                    met = ConditionEvaluator.allMet(expects, before, after, apps) ||
                        screen.awaitObs(step.timeoutMs.coerceAtMost(1500)) { ConditionEvaluator.allMet(expects, before, it, apps) }?.also { after = it } != null
                    out = retry
                }
            }

            tracker.record(key, kind, before.layoutSignature, after.layoutSignature, after.change(before), ok = met)
            obs = after
            if (!met) {
                val what = when (after.change(before)) {
                    ScreenChange.NONE -> "the screen did not change"
                    ScreenChange.CONTENT -> "the screen changed but not as expected"
                    ScreenChange.LAYOUT -> "a different screen is showing"
                }
                return handoff(HandoffReason.EXPECTATION, "Step ${i + 1} (${step.describe()}) ran, but expected ${expects.joinToString(" and ") { it.describe() }}: $what.")
            }
            done += StepResult(step.id, step.describe(), true, clock.now() - t0)

            // 9. Sensitive steps end the plan so the model sees the result before anything else happens
            if (out.risk != Risk.LOW && i < plan.steps.lastIndex) {
                return handoff(HandoffReason.CHECKPOINT, "Stopped after a ${out.risk.name.lowercase()}-risk step (${step.describe()}) so you can check the result.")
            }
        }
        return PlanOutcome.Completed(done.toList(), obs)
    }

    private fun kindOf(a: PlanAction) = when (a) {
        PlanAction.TAP, PlanAction.LONG_PRESS -> ActionKind.TAP
        PlanAction.PRESS_BACK -> ActionKind.BACK
        PlanAction.SCROLL -> ActionKind.SCROLL
        PlanAction.TYPE_TEXT -> ActionKind.TYPE
        PlanAction.OPEN_APP -> ActionKind.OPEN_APP
        else -> ActionKind.OTHER
    }
}

/** Compact text for the model after a plan: what ran, where it stopped and why, and the current screen. */
object PlanReport {
    fun format(plan: Plan, outcome: PlanOutcome, render: (Obs) -> String): String = buildString {
        val total = plan.steps.size
        when (outcome) {
            is PlanOutcome.Completed -> {
                append("PLAN COMPLETED: ${outcome.done.size}/$total steps. ").append(steps(outcome.done)).append('\n')
                outcome.finalObs?.let { append(render(it)) }
            }
            is PlanOutcome.Handoff -> {
                val label = if (outcome.reason == HandoffReason.CHECKPOINT) "PLAN PAUSED" else "PLAN STOPPED"
                append("$label at step ${outcome.stepIndex + 1}/$total [${outcome.reason.name.lowercase()}]. ").append(outcome.detail).append('\n')
                if (outcome.done.isNotEmpty()) append("Done: ").append(steps(outcome.done)).append('\n')
                append("Decide the next step from this screen; element ids below are current.\n")
                outcome.obs?.let { append(render(it)) }
            }
            is PlanOutcome.Denied -> {
                append("PLAN STOPPED at step ${outcome.stepIndex + 1}/$total: ${outcome.detail} Do not retry; ask the user what to do.\n")
                if (outcome.done.isNotEmpty()) append("Done: ").append(steps(outcome.done))
            }
        }
    }.trimEnd()

    private fun steps(done: List<StepResult>) = done.joinToString("; ") { it.label + (if (it.note.isNotBlank()) " (${it.note})" else "") }
}
