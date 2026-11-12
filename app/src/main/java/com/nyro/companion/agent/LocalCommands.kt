package com.nyro.companion.agent

import com.nyro.companion.NyroApp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Limited local-capability mode: handles common requests without Azure (not configured,
 * offline, or the endpoint failing). Everything here runs on-device through the same tools
 * the agent uses, so results are real readings and real actions.
 */
object LocalCommands {
    data class Result(val reply: String, val tool: String?)

    suspend fun handle(text: String): Result? {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        val reg = NyroApp.graph.tools
        suspend fun run(name: String, vararg a: Pair<String, Any?>) = reg.get(name)!!.run(reg.args(*a))

        Regex("^(?:please )?(?:open|launch|start) (?:the )?(.+?)(?: app)?$").find(t)?.let { m ->
            val r = run("open_app", "name" to m.groupValues[1])
            return Result(if (r.ok) "Opening ${m.groupValues[1]}." else r.text.removePrefix("FAILED: "), "open_app")
        }
        when {
            t.matches(Regex(".*\\b(go )?home( screen)?$")) -> return Result(if (run("press_home").ok) "Going home." else "I need Phone Control (accessibility) for that.", "press_home")
            t.matches(Regex("^(go )?back$")) -> return Result(if (run("press_back").ok) "Going back." else "I need Phone Control (accessibility) for that.", "press_back")
            t.contains("flashlight") || t.contains("torch") -> {
                val on = !(t.contains(" off") || t.contains("disable"))
                val r = run("set_flashlight", "on" to on)
                return Result(r.text.removePrefix("FAILED: "), "set_flashlight")
            }
            t.contains("battery") -> {
                val b = NyroApp.graph.device.battery()
                val temp = b["temperature_c"]?.let { ", ${it}°C" } ?: ""
                return Result("Battery is at ${b.s("percent")}%, ${b.s("status")}$temp. Thermal status ${b.s("thermal_status")}.", "get_battery")
            }
            t.contains("storage") || t.contains("space") -> {
                val s = NyroApp.graph.device.storage()
                return Result("You have ${s.s("free_gb")} GB free of ${s.s("total_gb")} GB (${s.s("used_percent")}% used).", "get_device_info")
            }
            t.contains("ram") || t.contains("memory usage") -> {
                val m = NyroApp.graph.device.memory()
                return Result("${m.s("available_gb")} GB of ${m.s("total_gb")} GB RAM is available.", "get_device_info")
            }
            t.contains("wifi") || t.contains("wi-fi") || t.contains("network") || t.contains("internet") || t.contains("bluetooth") -> {
                val n = NyroApp.graph.device.network()
                return Result("Connection: ${n.s("transport")}, internet ${if (n.s("validated_internet") == "true") "working" else "not validated"}. Wi-Fi ${onOff(n.s("wifi_enabled"))}, Bluetooth ${onOff(n.s("bluetooth_enabled"))}.", "get_network_status")
            }
            t.contains("sensor") -> {
                val s = NyroApp.graph.device.sensors()
                return Result("This phone reports ${s.s("count")} sensors. Open Device in NYRO to see them all.", "list_sensors")
            }
            t.contains("device") || t.contains("phone info") || t.contains("specs") || t.contains("which phone") || t.contains("android version") || t.contains("processor") || t.contains("cpu") -> {
                val d = NyroApp.graph.device.device()
                val cpu = d["cpu"] as JsonObject
                return Result("This is a ${d.s("manufacturer")} ${d.s("model")} on Android ${d.s("android_version")} (API ${d.s("api_level")}), ${cpu.s("cores")} CPU cores ${cpu["soc_model"]?.let { "(${(it as JsonPrimitive).content})" } ?: ""}.", "get_device_info")
            }
        }
        return null
    }

    private fun onOff(v: String) = if (v == "true") "on" else "off"
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull ?: "?"
}
