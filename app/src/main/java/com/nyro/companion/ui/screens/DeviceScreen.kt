package com.nyro.companion.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nyro.companion.NyroApp
import com.nyro.companion.ui.components.SectionLabel
import com.nyro.companion.ui.theme.GlassCard
import com.nyro.companion.ui.theme.Nyro
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Live, locally read device information. Works with no network and no Azure configuration. */
@Composable
fun DeviceScreen(onBack: () -> Unit) {
    val d = NyroApp.graph.device
    var device by remember { mutableStateOf<JsonObject?>(null) }
    var battery by remember { mutableStateOf<JsonObject?>(null) }
    var network by remember { mutableStateOf<JsonObject?>(null) }
    var sensors by remember { mutableStateOf<JsonObject?>(null) }
    val readings = remember { mutableStateMapOf<String, String>() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        sensors = withContext(Dispatchers.Default) { d.sensors() }
        while (true) {
            withContext(Dispatchers.Default) {
                device = d.device(); battery = d.battery(); network = d.network()
            }
            delay(3000)
        }
    }

    ScreenFrame("Device", onBack) {
        device?.let { dev ->
            Section("Phone") {
                KeyValue("Model", "${dev.s("manufacturer")} ${dev.s("model")}")
                KeyValue("Android", "${dev.s("android_version")} (API ${dev.s("api_level")})")
                KeyValue("Security patch", dev.s("security_patch"))
                KeyValue("Build", dev.s("build"))
                (dev["display"] as? JsonObject)?.let { KeyValue("Display", "${it.s("width_px")}×${it.s("height_px")} · ${it.s("density_dpi")} dpi · ${it.s("refresh_rate_hz")} Hz") }
                KeyValue("Uptime", "${dev.s("uptime_hours")} h")
            }
            (dev["cpu"] as? JsonObject)?.let { c ->
                Section("Processor") {
                    c["soc_model"]?.let { KeyValue("SoC", "${c.s("soc_manufacturer")} ${c.s("soc_model")}") }
                    KeyValue("Cores", c.s("cores"))
                    KeyValue("Max clocks (MHz)", c.s("max_freq_mhz"))
                    KeyValue("ABIs", c.s("abis"))
                    KeyValue("Hardware", c.s("hardware"))
                }
            }
            Section("Memory & storage") {
                (dev["memory"] as? JsonObject)?.let { KeyValue("RAM", "${it.s("available_gb")} GB free of ${it.s("total_gb")} GB (${it.s("used_percent")}% used)") }
                (dev["storage"] as? JsonObject)?.let { KeyValue("Storage", "${it.s("free_gb")} GB free of ${it.s("total_gb")} GB (${it.s("used_percent")}% used)") }
            }
        }
        battery?.let { b ->
            Section("Battery") {
                KeyValue("Level", "${b.s("percent")}%")
                KeyValue("Status", "${b.s("status")} · ${b.s("plugged")}")
                b["temperature_c"]?.let { KeyValue("Temperature", "${b.s("temperature_c")} °C") }
                b["voltage_mv"]?.let { KeyValue("Voltage", "${b.s("voltage_mv")} mV") }
                b["current_now_ma"]?.let { KeyValue("Current", "${b.s("current_now_ma")} mA") }
                KeyValue("Health", b.s("health"))
                KeyValue("Thermal", b.s("thermal_status"))
                KeyValue("Battery saver", if (b.s("power_save_mode") == "true") "on" else "off")
            }
        }
        network?.let { n ->
            Section("Connectivity") {
                KeyValue("Active", "${n.s("transport")}${if (n.s("metered") == "true") " (metered)" else ""}")
                KeyValue("Internet", if (n.s("validated_internet") == "true") "working" else "not validated")
                n["downlink_mbps"]?.let { KeyValue("Link (est.)", "↓ ${n.s("downlink_mbps")} / ↑ ${n.s("uplink_mbps")} Mbps") }
                KeyValue("Wi-Fi", if (n.s("wifi_enabled") == "true") "on" else "off")
                KeyValue("Bluetooth", if (n.s("bluetooth_enabled") == "true") "on" else "off")
                KeyValue("Ringer", n.s("ringer_mode"))
            }
        }
        sensors?.let { s ->
            Section("Sensors (${s.s("count")})") {
                val readable = listOf("accelerometer", "gyroscope", "magnetic_field", "light", "proximity", "pressure", "gravity", "rotation_vector")
                readable.forEach { key ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(key.replace('_', ' '), color = Nyro.TextHi, style = MaterialTheme.typography.bodyMedium)
                            readings[key]?.let { Text(it, color = Nyro.Cyan, style = MaterialTheme.typography.bodySmall) }
                        }
                        TextButton(onClick = {
                            scope.launch {
                                val r = d.readSensor(key)
                                readings[key] = r["error"]?.jsonPrimitive?.content
                                    ?: ((r["values"] as? JsonArray)?.joinToString(", ") { it.jsonPrimitive.content } + " " + r.s("unit"))
                            }
                        }) { Text("Read") }
                    }
                }
                Gap(6)
                Text("All sensors", color = Nyro.TextLow, style = MaterialTheme.typography.labelSmall)
                (s["sensors"] as? JsonArray)?.forEach { e ->
                    val o = e.jsonObject
                    Text("${o.s("name")} · ${o.s("type")}", color = Nyro.TextMid, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    SectionLabel(title, Modifier.padding(top = 10.dp))
    GlassCard(Modifier.fillMaxWidth(), padding = 14) { Column { content() } }
}

private fun JsonObject.s(k: String): String = when (val v: JsonElement? = this[k]) {
    is JsonPrimitive -> v.content
    null -> "—"
    else -> v.toString()
}
