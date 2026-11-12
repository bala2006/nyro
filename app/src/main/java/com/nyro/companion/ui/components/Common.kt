package com.nyro.companion.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.nyro.companion.agent.Step
import com.nyro.companion.agent.StepStatus
import com.nyro.companion.tools.Risk
import com.nyro.companion.ui.theme.Nyro

@Composable
fun ChatInput(value: String, onValueChange: (String) -> Unit, onSend: () -> Unit, modifier: Modifier = Modifier, placeholder: String = "Ask or tell NYRO what to do") {
    OutlinedTextField(
        value = value, onValueChange = onValueChange, modifier = modifier,
        placeholder = { Text(placeholder, color = Nyro.TextLow) },
        maxLines = 4,
        shape = RoundedCornerShape(24.dp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        trailingIcon = {
            if (value.isNotBlank()) IconButton(onClick = onSend) { Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = Nyro.Cyan) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Nyro.Cyan.copy(alpha = 0.7f), unfocusedBorderColor = Nyro.GlassEdge,
            focusedContainerColor = Color(0x22000000), unfocusedContainerColor = Color(0x22000000), cursorColor = Nyro.Cyan,
        ),
    )
}

fun riskColor(r: Risk) = when (r) { Risk.LOW -> Nyro.Cyan; Risk.MEDIUM -> Nyro.Amber; Risk.HIGH -> Nyro.Danger }

@Composable
fun ConfirmationCard(risk: Risk, text: String, onAllow: () -> Unit, onDeny: () -> Unit) {
    val c = riskColor(risk)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(c.copy(alpha = 0.12f))
            .border(1.dp, c.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Shield, null, tint = c, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (risk == Risk.HIGH) "HIGH-RISK ACTION" else "CONFIRM ACTION", style = MaterialTheme.typography.labelSmall, color = c)
        }
        Spacer(Modifier.size(6.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Nyro.TextHi)
        Spacer(Modifier.size(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = onDeny, modifier = Modifier.weight(1f)) { Text("Don't", color = Nyro.TextHi) }
            Button(onClick = onAllow, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = c, contentColor = Nyro.Void)) { Text("Allow") }
        }
    }
}

@Composable
fun StepList(steps: List<Step>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        steps.forEach { s ->
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when (s.status) {
                        StepStatus.RUNNING -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Nyro.Cyan)
                        StepStatus.DONE -> Icon(Icons.Default.Check, null, tint = Nyro.Ok, modifier = Modifier.size(16.dp))
                        StepStatus.FAILED -> Icon(Icons.Default.Close, null, tint = Nyro.Danger, modifier = Modifier.size(16.dp))
                        StepStatus.DENIED -> Icon(Icons.Default.Block, null, tint = Nyro.Amber, modifier = Modifier.size(16.dp))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, color = if (s.status == StepStatus.RUNNING) Nyro.TextHi else Nyro.TextMid)
                    if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.bodySmall, color = if (s.status == StepStatus.FAILED) Nyro.Danger.copy(alpha = 0.85f) else Nyro.TextLow)
                }
                if (s.risk != Risk.LOW) {
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.padding(top = 4.dp).size(6.dp).clip(CircleShape).background(riskColor(s.risk)))
                }
            }
        }
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Nyro.TextLow, modifier = modifier.padding(start = 4.dp, bottom = 6.dp))
