package com.nyro.companion.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nyro.companion.NyroApp
import com.nyro.companion.agent.UsageMeter
import com.nyro.companion.agent.UsageState
import com.nyro.companion.data.NyroSettings
import com.nyro.companion.ui.Routes
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.components.StepList
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.launch

/** Estimated spend from token counts and the prices in settings (per 1M tokens). */
fun estimateCost(u: UsageState, s: NyroSettings): Double =
    ((u.input - u.cached).coerceAtLeast(0) * s.priceInput + u.cached * s.priceCached + u.output * s.priceOutput) / 1_000_000.0

/**
 * Activity tab: what NYRO is doing now, token use and estimated cost, what was last sent to Azure,
 * and links to the task and conversation history.
 */
@Composable
fun ActivityScreen(go: (String) -> Unit) {
    val g = NyroApp.graph
    val st by g.agent.state.collectAsStateWithLifecycle()
    val usage by g.usage.state.collectAsStateWithLifecycle()
    val settings by g.settings.settings.collectAsStateWithLifecycle()
    val sentLog by g.agent.sentLog.collectAsStateWithLifecycle()
    val undoLabel by g.agent.undoLabel.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val paused by g.agent.paused.collectAsStateWithLifecycle()
    var editPrices by remember { mutableStateOf(false) }

    ScreenFrame("Activity", onBack = null) {
        SectionLabel("Now")
        GlassCard(Modifier.fillMaxWidth(), accent = if (st.taskActive) Nyro.Cyan.copy(alpha = 0.5f) else Nyro.GlassEdge) {
            if (st.taskActive || st.steps.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    st.confirmation?.let { c ->
                        com.nyro.companion.ui.components.ConfirmationCard(c.risk, c.text, onAllow = { g.agent.answerConfirmation(true) }, onDeny = { g.agent.answerConfirmation(false) })
                    }
                    Text(st.request, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                    StepList(st.steps)
                    if (st.taskActive) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { g.agent.togglePause() }) { Text(if (paused) "Resume" else "Pause") }
                        OutlinedButton(onClick = { g.agent.stop() }) { Text("Stop", color = Nyro.Danger) }
                    }
                    undoLabel?.let { label -> OutlinedButton(onClick = { scope.launch { g.agent.undoLast() } }) { Text("Undo: $label") } }
                }
            } else {
                Text("Idle. Each step NYRO takes appears here while it works.", color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Gap(12)
        SectionLabel("Tokens")
        GlassCard(Modifier.fillMaxWidth()) {
            Column {
                KeyValue("This task", UsageMeter.fmt(usage.task))
                KeyValue("All time", UsageMeter.fmt(usage.total))
                KeyValue("Input / output", "${UsageMeter.fmt(usage.input)} / ${UsageMeter.fmt(usage.output)}")
                KeyValue("Cache hit (last turn)", "${usage.cachePct}%")
                KeyValue("Speed", if (usage.tokensPerSec > 0f) "%.0f tokens/s".format(usage.tokensPerSec) else "—")
            }
        }

        Gap(12)
        SectionLabel("Estimated cost")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                KeyValue("All time", "$%.4f".format(estimateCost(usage, settings)))
                Text(
                    "Uses the prices below (per 1M tokens): input ${"%.2f".format(settings.priceInput)}, cached ${"%.2f".format(settings.priceCached)}, output ${"%.2f".format(settings.priceOutput)}. Check them against your Azure pricing page.",
                    color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { editPrices = true }) { Text("Edit prices") }
            }
        }

        Gap(12)
        SectionLabel("Privacy")
        GlassCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sentLog.isEmpty()) Text("Nothing has been sent to Azure yet.", color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                sentLog.take(5).forEach { Text(it, color = Nyro.TextHi, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Your screen content is sent only when a task needs it. Screenshots are sent only when accessibility data isn't enough.",
                    color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        DiagnosticsSection()

        Gap(12)
        SectionLabel("History")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { go(Routes.TASKS) }, modifier = Modifier.weight(1f)) { Text("Tasks") }
            OutlinedButton(onClick = { go(Routes.HISTORY) }, modifier = Modifier.weight(1f)) { Text("Conversations") }
        }
    }

    if (editPrices) PriceDialog(settings, onDismiss = { editPrices = false })
}

@Composable
private fun PriceDialog(s: NyroSettings, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf("%.2f".format(s.priceInput)) }
    var cached by remember { mutableStateOf("%.2f".format(s.priceCached)) }
    var output by remember { mutableStateOf("%.2f".format(s.priceOutput)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Nyro.Glass,
        title = { Text("Prices per 1M tokens") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(input, { input = it }, label = { Text("Input") }, singleLine = true)
                OutlinedTextField(cached, { cached = it }, label = { Text("Cached input") }, singleLine = true)
                OutlinedTextField(output, { output = it }, label = { Text("Output") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val a = input.toFloatOrNull(); val b = cached.toFloatOrNull(); val c = output.toFloatOrNull()
                if (a != null && b != null && c != null && a >= 0 && b >= 0 && c >= 0) {
                    scope.launch { NyroApp.graph.settings.savePrices(a, b, c) }
                    onDismiss()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
