package com.nyro.companion.exec

import com.nyro.companion.perf.Clock

/** A named screen state, recognised from semantic conditions (labels, roles, view ids), never coordinates. */
data class StateSpec(val id: String, val description: String, val conditions: List<Condition>)

/** From [from], run [step]; the screen must then be in state [to]. */
data class Transition(val from: String, val step: PlanStep, val to: String)

/**
 * An app-specific workflow: a small state machine over semantic screen states. The model chooses the workflow and
 * the goal; Kotlin walks the states, verifying each one. Anything unrecognised is handed back to the model.
 *
 * [verified] says whether the state conditions were checked against the real app UI on a device.
 */
data class AppWorkflow(
    val id: String,
    val title: String,
    val appName: String,
    val packages: Set<String>,
    val states: List<StateSpec>,
    val transitions: List<Transition>,
    val goal: String,
    val verified: Boolean,
    val maxTransitions: Int = 8,
) {
    fun state(id: String): StateSpec? = states.firstOrNull { it.id == id }
}

sealed interface WorkflowOutcome {
    val steps: List<StepResult>

    data class Reached(val state: String, override val steps: List<StepResult>, val obs: Obs?) : WorkflowOutcome

    /** Unknown state, missing transition, failure or loop: the model decides what to do next. */
    data class Escalate(val reason: String, override val steps: List<StepResult>, val obs: Obs?) : WorkflowOutcome

    data class Denied(val detail: String, override val steps: List<StepResult>) : WorkflowOutcome
}

class WorkflowRunner(
    private val screen: ScreenPort,
    private val actions: ActionPort,
    private val tracker: ProgressTracker,
    private val clock: Clock,
    private val apps: AppResolver,
) {
    private val executor = PlanExecutor(screen, actions, tracker, clock, apps)

    fun classify(w: AppWorkflow, obs: Obs): StateSpec? =
        w.states.firstOrNull { s -> ConditionEvaluator.allMet(s.conditions, obs, obs, apps) }

    suspend fun run(w: AppWorkflow): WorkflowOutcome {
        val steps = ArrayList<StepResult>()
        var obs = screen.observe()

        // 1. The app must be in front.
        if (obs.packageName !in w.packages) {
            val open = PlanStep(id = "open", action = PlanAction.OPEN_APP, app = w.appName, expect = listOf(Condition.App(w.appName)), timeoutMs = 6000)
            when (val r = executor.run(Plan(w.title, listOf(open)))) {
                is PlanOutcome.Completed -> { steps += r.done; obs = r.finalObs ?: screen.observe(0) }
                is PlanOutcome.Handoff -> return WorkflowOutcome.Escalate("Could not bring ${w.appName} to the front: ${r.detail}", steps + r.done, r.obs)
                is PlanOutcome.Denied -> return WorkflowOutcome.Denied(r.detail, steps + r.done)
            }
        }

        // 2. Walk states until the goal is reached.
        repeat(w.maxTransitions + 1) {
            val state = classify(w, obs)
                ?: return WorkflowOutcome.Escalate("The ${w.appName} screen is not one this workflow knows (the app UI may have changed).", steps, obs)
            if (state.id == w.goal) return WorkflowOutcome.Reached(state.id, steps, obs)
            val t = w.transitions.firstOrNull { it.from == state.id }
                ?: return WorkflowOutcome.Escalate("No known way forward from \"${state.description}\".", steps, obs)
            val target = w.state(t.to)
            val step = if (t.step.expect.isEmpty() && target != null) t.step.copy(expect = target.conditions) else t.step
            when (val r = executor.run(Plan(w.title, listOf(step)))) {
                is PlanOutcome.Completed -> { steps += r.done; obs = r.finalObs ?: screen.observe(0) }
                is PlanOutcome.Handoff -> return WorkflowOutcome.Escalate("From \"${state.description}\": ${r.detail}", steps + r.done, r.obs)
                is PlanOutcome.Denied -> return WorkflowOutcome.Denied(r.detail, steps + r.done)
            }
        }
        return WorkflowOutcome.Escalate("The workflow did not reach \"${w.goal}\" within ${w.maxTransitions} transitions.", steps, obs)
    }
}

object WorkflowReport {
    fun format(w: AppWorkflow, outcome: WorkflowOutcome, render: (Obs) -> String): String = buildString {
        when (outcome) {
            is WorkflowOutcome.Reached -> {
                append("WORKFLOW ${w.id} REACHED \"${w.state(outcome.state)?.description ?: outcome.state}\" in ${outcome.steps.size} steps.\n")
                outcome.obs?.let { append(render(it)) }
            }
            is WorkflowOutcome.Escalate -> {
                append("WORKFLOW ${w.id} STOPPED: ${outcome.reason}\n")
                if (!w.verified) append("(This workflow has not been verified on a device; treat a mismatch as expected and continue manually.)\n")
                outcome.obs?.let { append(render(it)) }
            }
            is WorkflowOutcome.Denied -> append("WORKFLOW ${w.id} STOPPED: ${outcome.detail} Do not retry; ask the user what to do.")
        }
    }.trimEnd()
}
