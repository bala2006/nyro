package com.nyro.companion.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.nyro.companion.NyroApp
import com.nyro.companion.data.ProviderConfig
import com.nyro.companion.data.ProviderType
import com.nyro.companion.data.WireApi
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.launch

@Composable
fun ProviderScreen(onBack: () -> Unit) {
    val g = NyroApp.graph
    val saved = g.settings.current
    val scope = rememberCoroutineScope()
    var type by remember { mutableStateOf(saved.provider.type) }
    var endpoint by remember { mutableStateOf(saved.provider.endpoint) }
    var deployment by remember { mutableStateOf(saved.provider.deployment) }
    var apiVersion by remember { mutableStateOf(saved.provider.apiVersion) }
    var vision by remember { mutableStateOf(saved.provider.visionDeployment) }
    var wireApi by remember { mutableStateOf(saved.provider.wireApi) }
    var effort by remember { mutableStateOf(saved.provider.reasoningEffort) }
    var key by remember { mutableStateOf("") }
    val hasKey = remember { mutableStateOf(g.keys.hasApiKey()) }
    var showKey by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf(saved.lastConnectionStatus) }
    var capability by remember { mutableStateOf(saved.capabilityInfo) }
    var testing by remember { mutableStateOf(false) }
    var ok by remember { mutableStateOf<Boolean?>(null) }

    fun cfg() = ProviderConfig(
        type, endpoint.trim(), deployment.trim(), apiVersion.trim(), vision.trim(),
        wireApi = wireApi, reasoningEffort = effort,
        responsesDetectedFor = g.settings.current.provider.responsesDetectedFor,
    )

    suspend fun save(): String? {
        g.ai.validate(cfg())?.let { return it }
        if (key.isNotBlank()) { g.keys.setApiKey(key.trim()); key = ""; hasKey.value = true }
        if (!hasKey.value) return "API key is required."
        g.settings.saveProvider(cfg())
        return null
    }

    ScreenFrame("AI Provider", onBack) {
        Text("NYRO talks directly to your own Azure deployment over HTTPS. There is no NYRO server in between.", color = Nyro.TextMid, style = MaterialTheme.typography.bodyMedium)
        Gap(14)
        SectionLabel("Provider")
        ProviderType.entries.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable {
                    type = t
                    if (t == ProviderType.AZURE_AI_FOUNDRY && apiVersion == ProviderConfig.DEFAULT_API_VERSION) apiVersion = ProviderConfig.DEFAULT_FOUNDRY_API_VERSION
                    if (t == ProviderType.AZURE_OPENAI && apiVersion == ProviderConfig.DEFAULT_FOUNDRY_API_VERSION) apiVersion = ProviderConfig.DEFAULT_API_VERSION
                }.padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(type == t, onClick = null, colors = RadioButtonDefaults.colors(selectedColor = Nyro.Cyan))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(t.label, color = Nyro.TextHi, style = MaterialTheme.typography.titleSmall)
                    Text(t.help, color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Gap(10)
        SectionLabel("Connection")
        Field("Endpoint URL", endpoint, { endpoint = it }, when (type) {
            ProviderType.AZURE_AI_FOUNDRY -> "https://<resource>.services.ai.azure.com"
            else -> "https://<resource>.openai.azure.com"
        }, KeyboardType.Uri)
        Field(if (type == ProviderType.AZURE_OPENAI) "Deployment name" else "Deployment / model name", deployment, { deployment = it }, "e.g. gpt-4o")
        if (type != ProviderType.AZURE_OPENAI_V1) Field("API version", apiVersion, { apiVersion = it }, ProviderConfig.DEFAULT_API_VERSION)
        Field("Vision deployment (optional)", vision, { vision = it }, "Leave empty if the main deployment supports images")
        SectionLabel("Request API")
        Chips(WireApi.entries.map { it.label }, WireApi.entries.indexOf(wireApi)) { wireApi = WireApi.entries[it] }
        Text(
            when (wireApi) {
                WireApi.AUTO -> "Starts with Chat Completions and switches to the Responses API automatically when the model requires it for tool calling (e.g. GPT-5.6+ / GPT-6 reasoning models)."
                WireApi.CHAT -> "Always Chat Completions. Reasoning models that refuse tools here get reasoning_effort=none."
                WireApi.RESPONSES -> "Always the Responses API at {endpoint}/openai/v1/responses (stateless, store=false)."
            } + if (g.settings.current.provider.responsesDetectedFor.isNotBlank()) "\nDetected: ${g.settings.current.provider.responsesDetectedFor} uses the Responses API." else "",
            color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp),
        )
        SectionLabel("Reasoning effort")
        val efforts = listOf("" to "Default", "none" to "None", "minimal" to "Minimal", "low" to "Low", "medium" to "Medium", "high" to "High")
        Chips(efforts.map { it.second }, efforts.indexOfFirst { it.first == effort }.coerceAtLeast(0)) { effort = efforts[it].first }
        Text("Lower effort answers faster; higher effort plans multi-step phone tasks more carefully. Ignored by models without reasoning.",
            color = Nyro.TextLow, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 10.dp))
        OutlinedTextField(
            value = key, onValueChange = { key = it }, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            label = { Text(if (hasKey.value) "API key (saved — enter to replace)" else "API key") },
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = { IconButton(onClick = { showKey = !showKey }) { Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showKey) "Hide key" else "Show key") } },
            colors = fieldColors(),
        )
        g.ai.hostWarning(cfg())?.let { Text(it, color = Nyro.Amber, style = MaterialTheme.typography.bodySmall) }

        Gap(6)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = {
                scope.launch {
                    val err = save()
                    status = err ?: "Saved."; ok = if (err == null) null else false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Nyro.Blue)) { Text("Save") }
            Button(enabled = !testing, onClick = {
                scope.launch {
                    val err = save()
                    if (err != null) { status = err; ok = false; return@launch }
                    testing = true; status = "Testing connection…"; ok = null
                    val r = g.ai.testConnection(cfg())
                    testing = false
                    status = r.summary; capability = r.capability; ok = r.ok
                    g.settings.setConnectionStatus(r.summary, r.capability)
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = Nyro.Cyan, contentColor = Nyro.Void)) {
                if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Nyro.Void) else Text("Test connection")
            }
        }
        Gap(12)
        if (status.isNotBlank() || capability.isNotBlank()) {
            GlassCard(Modifier.fillMaxWidth(), accent = when (ok) { true -> Nyro.Ok.copy(alpha = 0.5f); false -> Nyro.Danger.copy(alpha = 0.5f); null -> Nyro.GlassEdge }) {
                Column {
                    Text("Connection status", color = Nyro.TextLow, style = MaterialTheme.typography.labelSmall)
                    Text(status, color = when (ok) { true -> Nyro.Ok; false -> Nyro.Danger; null -> Nyro.TextHi }, style = MaterialTheme.typography.bodyMedium)
                    if (capability.isNotBlank()) {
                        Gap(6)
                        Text("Model capabilities", color = Nyro.TextLow, style = MaterialTheme.typography.labelSmall)
                        Text(capability, color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
        Gap(14)
        OutlinedButton(onClick = {
            scope.launch {
                g.keys.clear(); g.settings.clearProvider(); hasKey.value = false
                endpoint = ""; deployment = ""; vision = ""; key = ""; apiVersion = ProviderConfig.DEFAULT_API_VERSION
                wireApi = WireApi.AUTO; effort = ""
                status = "Credentials cleared from this phone."; capability = ""; ok = null
            }
        }) { Text("Clear credentials", color = Nyro.Danger) }
        Gap(14)
        GlassCard(Modifier.fillMaxWidth(), accent = Nyro.Amber.copy(alpha = 0.35f), padding = 14) {
            Column {
                Text("About your key", color = Nyro.Amber, style = MaterialTheme.typography.titleSmall)
                Text(
                    "The key is encrypted with an Android Keystore key and never logged or shown again. A key stored on a personal device is still not fully protected against a rooted or compromised phone. Use a key you can rotate in the Azure portal, and set spending limits on the resource.",
                    color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
                )
                Gap(6)
                Text(
                    "NYRO needs a model that supports tool (function) calling. Newer reasoning models (GPT-5.6+, GPT-6) only allow tools through the Responses API; Auto handles this. Image input is needed for screenshot understanding.",
                    color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Chips(labels: List<String>, selected: Int, onPick: (Int) -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
    ) {
        labels.forEachIndexed { i, l ->
            androidx.compose.material3.FilterChip(
                selected = i == selected, onClick = { onPick(i) }, label = { Text(l) },
                colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = Nyro.Cyan.copy(alpha = 0.25f), selectedLabelColor = Nyro.TextHi),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, hint: String, kb: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, placeholder = { Text(hint, color = Nyro.TextLow) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        keyboardOptions = KeyboardOptions(keyboardType = kb, autoCorrectEnabled = false),
        colors = fieldColors(),
    )
}

@Composable
fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = Nyro.Cyan, unfocusedBorderColor = Nyro.GlassEdge, focusedLabelColor = Nyro.Cyan, cursorColor = Nyro.Cyan,
)
