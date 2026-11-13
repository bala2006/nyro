package com.nyro.companion.exec

/**
 * Built-in workflows. Each is data: states are semantic conditions, transitions are plan steps. Adding an app
 * means adding one [AppWorkflow]; the engine, safety pipeline, loop detection and escalation are shared.
 */
object Workflows {
    val all: List<AppWorkflow> by lazy { listOf(duolingoStartLesson()) }

    fun find(id: String?): AppWorkflow? = all.firstOrNull { it.id == id }

    private fun label(text: String, mode: MatchMode = MatchMode.CONTAINS) = Selector(label = text, mode = mode)

    /**
     * Duolingo: get from the home path into a running lesson. Answering questions needs reasoning, so the workflow
     * stops once a lesson is active and returns the compact screen to the model, which can then answer a whole
     * question (select answer, Check, Continue) as one `run_plan` call.
     *
     * UNVERIFIED: the state conditions come from the labels seen in the logs ("SECTION 2, UNIT 1", answer tiles,
     * Check/Continue buttons). They have not been confirmed against the app on the target phone. If Duolingo's UI
     * differs, the runner reports an unknown screen and the model continues; nothing is tapped by coordinates.
     */
    fun duolingoStartLesson() = AppWorkflow(
        id = "duolingo_start_lesson",
        title = "Start a Duolingo lesson",
        appName = "Duolingo",
        packages = setOf("com.duolingo"),
        states = listOf(
            // Order matters: the first matching state wins.
            StateSpec(
                "lesson", "a lesson is running",
                listOf(Condition.App("com.duolingo"), Condition.AnyOf(listOf(
                    Condition.Visible(label("check", MatchMode.EXACT)),
                    Condition.Visible(label("skip", MatchMode.EXACT)),
                ))),
            ),
            StateSpec(
                "start_sheet", "a lesson start panel is open",
                listOf(Condition.App("com.duolingo"), Condition.Visible(Selector(label = "start", mode = MatchMode.STARTS, role = "Button"))),
            ),
            StateSpec(
                "path", "the learning path",
                listOf(Condition.App("com.duolingo"), Condition.Visible(label("section"))),
            ),
        ),
        transitions = listOf(
            Transition(
                from = "start_sheet",
                step = PlanStep(id = "start", action = PlanAction.TAP, target = Selector(label = "start", mode = MatchMode.STARTS, role = "Button"), timeoutMs = 5000),
                to = "lesson",
            ),
        ),
        goal = "lesson",
        verified = false,
    )
}
