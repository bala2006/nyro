package com.nyro.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nyro.companion.NyroApp
import com.nyro.companion.perf.Benchmark
import com.nyro.companion.perf.Perf
import com.nyro.companion.perf.Summary
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun fmt(s: Summary) = "p50 ${s.p50} · p95 ${s.p95} · p99 ${s.p99} ms  (n=${s.n})"

/**
 * Developer diagnostics: rolling p50/p95/p99 for every timed stage, plus a local benchmark. Each task's own breakdown
 * (Azure calls, tokens, per-stage totals) is also stored in that task's log under Tasks.
 */
@Composable
fun DiagnosticsSection() {
    val g = NyroApp.graph
    var tick by remember { mutableIntStateOf(0) }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { delay(2000); tick++ } }

    @Suppress("UNUSED_EXPRESSION") tick
    val rows = Perf.table()
    val bench = Perf.benchTable()

    Gap(12)
    SectionLabel("Performance diagnostics")
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (rows.isEmpty()) Text("No timings yet. Run a task and they appear here.", color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium)
            rows.forEach { (name, s) ->
                Column {
                    Text(name.replace('_', ' '), color = Nyro.TextHi, style = MaterialTheme.typography.bodySmall)
                    Text(fmt(s), color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                }
            }
            Text(
                "Monotonic clock (SystemClock.elapsedRealtime). 'http headers' includes uploading the request, 'ttft' is time to the first generated output. Samples are kept on this phone only.",
                color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = { Perf.reset() }) { Text("Clear timings") }
        }
    }

    Gap(12)
    SectionLabel("Local benchmark")
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            bench.forEach { (name, s) ->
                Column {
                    Text(name, color = Nyro.TextHi, style = MaterialTheme.typography.bodySmall)
                    Text(fmt(s), color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (running) Text("Running: $progress", color = Nyro.Cyan, style = MaterialTheme.typography.bodySmall)
            else if (confirm) {
                Text("This opens YouTube, Duolingo and NextStep 5 times each and reads your battery and notifications. Nothing is sent to Azure.", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = {
                    confirm = false; running = true
                    g.scope.launch(Dispatchers.Default) {
                        try { Benchmark.run(g, g.agent, 5) { progress = it } } finally { running = false; progress = "" }
                    }
                }) { Text("Start") }
            } else {
                OutlinedButton(onClick = { confirm = true }, modifier = Modifier.padding(top = 2.dp)) { Text("Run local benchmark") }
            }
            Text(
                "Not scriptable: ${Benchmark.manualScenarios.joinToString("; ")}. Their timing is saved in each task's log under Tasks.",
                color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
