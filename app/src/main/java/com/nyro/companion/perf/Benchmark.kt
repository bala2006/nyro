package com.nyro.companion.perf

import com.nyro.companion.agent.Agent
import com.nyro.companion.core.AppGraph
import kotlinx.coroutines.delay

/**
 * Repeatable on-device benchmark for requests that are fully local. Each scenario runs the real request path (router,
 * tool pipeline, Android calls) [iterations] times and records the end-to-end time as "bench:<name>", so p50/p95/p99 show up
 * in Diagnostics. It opens real apps: the user starts it explicitly.
 *
 * Scenarios that need Azure and a live app (a lesson, a pending office) cannot be scripted here. Their timing is recorded
 * automatically in each task's history entry; see docs/BENCHMARKS.md for how to repeat them.
 */
object Benchmark {
    data class Scenario(val name: String, val request: String, val returnHome: Boolean)

    val scenarios = listOf(
        Scenario("Open YouTube", "open YouTube", returnHome = true),
        Scenario("Open Duolingo", "open Duolingo", returnHome = true),
        Scenario("Find and open NextStep", "find and open NextStep", returnHome = true),
        Scenario("Read battery percentage", "what is my battery percentage", returnHome = false),
        Scenario("Read notifications", "read my notifications", returnHome = false),
    )

    val manualScenarios = listOf("Navigate to a Japanese lesson (Duolingo)", "Find a pending office (NeuroPath)")

    data class Result(val name: String, val runs: Int, val skipped: String?, val summary: Summary?)

    suspend fun run(g: AppGraph, agent: Agent, iterations: Int = 5, onProgress: (String) -> Unit = {}): List<Result> {
        val out = ArrayList<Result>()
        for (sc in scenarios) {
            val times = ArrayList<Long>()
            var skipped: String? = null
            for (i in 1..iterations) {
                onProgress("${sc.name} ($i/$iterations)")
                val t0 = Perf.clock.now()
                val reply = runCatching { agent.benchmarkLocal(sc.request) }.getOrNull()
                val ms = Perf.clock.now() - t0
                if (reply == null) { skipped = "needs Azure or the app is not installed"; break }
                times += ms
                Perf.record(Perf.BENCH_PREFIX + sc.name, ms, toTrace = false)
                if (sc.returnHome) {
                    g.tools.get("press_home")?.let { runCatching { it.run(g.tools.args()) } }
                    delay(600)
                }
            }
            out += Result(sc.name, times.size, skipped, if (times.isEmpty()) null else summarize(times.toLongArray()))
        }
        Perf.flush()
        return out
    }
}
