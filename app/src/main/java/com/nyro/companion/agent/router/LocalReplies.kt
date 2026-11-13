package com.nyro.companion.agent.router

import com.nyro.companion.device.AppMatch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What a local tool call produced. */
data class LocalOutcome(val ok: Boolean, val text: String, val denied: Boolean = false)

/**
 * Plain-language replies for locally handled requests, composed from the real tool results. No model involved.
 * Replies are English; requests in other scripts are not routed locally (they go to Azure, which answers in the user's language).
 */
object LocalReplies {
    private val json = Json { ignoreUnknownKeys = true }

    fun compose(intents: List<LocalIntent>, outcomes: List<List<LocalOutcome>>, nowMillis: Long, locale: Locale = Locale.getDefault()): String =
        intents.mapIndexed { i, intent -> one(intent, outcomes.getOrElse(i) { emptyList() }, nowMillis, locale) }.joinToString(" ")

    private fun one(intent: LocalIntent, results: List<LocalOutcome>, now: Long, locale: Locale): String {
        results.firstOrNull { it.denied }?.let { return "Okay, I didn't do that." }
        results.firstOrNull { !it.ok }?.let { return failure(it.text) }
        val r = results.firstOrNull()?.text.orEmpty()
        return when (intent) {
            is LocalIntent.OpenApp -> "Opened ${intent.app.label}."
            is LocalIntent.FindApp -> when (val m = intent.match) {
                is AppMatch.Hit -> "${m.app.label} is installed."
                is AppMatch.Ambiguous -> "I found ${m.candidates.size} apps like that: ${m.candidates.joinToString { it.label }}."
                AppMatch.None -> "I couldn't find an app called ${intent.query}."
            }
            LocalIntent.Battery -> obj(r)?.let { b ->
                val plugged = b.s("plugged")?.takeIf { it != "unplugged" }
                val state = when (b.s("status")) {
                    "charging" -> "charging" + (plugged?.let { " ($it)" } ?: "")
                    "full" -> "full"
                    "discharging", "not charging" -> "not charging"
                    else -> null
                }
                "Battery is at ${b.s("percent")}%" + (state?.let { " and $it" } ?: "") + (b.s("temperature_c")?.let { ", $it°C" } ?: "") + "."
            } ?: "I couldn't read the battery."
            LocalIntent.Storage -> obj(r)?.get("storage")?.jsonObject?.let { s ->
                "You have ${s.s("free_gb")} GB free of ${s.s("total_gb")} GB (${s.s("used_percent")}% used)."
            } ?: "I couldn't read storage."
            LocalIntent.Memory -> obj(r)?.get("memory")?.jsonObject?.let { m ->
                "${m.s("available_gb")} GB of ${m.s("total_gb")} GB RAM is available."
            } ?: "I couldn't read memory."
            LocalIntent.Network -> obj(r)?.let { n ->
                val internet = if (n.s("validated_internet") == "true") "working" else "not validated"
                "Connected via ${n.s("transport")}, internet $internet. Wi-Fi is ${onOff(n.s("wifi_enabled"))}, Bluetooth is ${onOff(n.s("bluetooth_enabled"))}."
            } ?: "I couldn't read the network state."
            LocalIntent.DeviceInfo -> obj(r)?.let { d ->
                val cpu = d["cpu"]?.jsonObject
                "This is a ${d.s("manufacturer")} ${d.s("model")} on Android ${d.s("android_version")} (API ${d.s("api_level")}), ${cpu?.s("cores")} CPU cores" +
                    (cpu?.s("soc_model")?.let { " ($it)" } ?: "") + "."
            } ?: "I couldn't read device info."
            LocalIntent.Sensors -> r.let { text ->
                val n = Regex("\"count\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)
                if (n != null) "This phone reports $n sensors. Open Device in NYRO to see them all." else "I couldn't list the sensors."
            }
            LocalIntent.Notifications -> notifications(r)
            is LocalIntent.Time -> {
                val d = Date(now)
                val time = SimpleDateFormat("h:mm a", locale).format(d)
                val date = SimpleDateFormat("EEEE, d MMMM", locale).format(d)
                when {
                    intent.time && intent.date -> "It's $time on $date."
                    intent.time -> "It's $time."
                    else -> "Today is $date."
                }
            }
            LocalIntent.Home -> "Went home."
            LocalIntent.Back -> "Went back."
            LocalIntent.Recents -> "Opened recent apps."
            LocalIntent.NotificationShade -> "Opened the notification shade."
            LocalIntent.QuickSettings -> "Opened quick settings."
            LocalIntent.LockScreen -> "Locked the screen."
            is LocalIntent.Flashlight -> "Flashlight ${if (intent.on) "on" else "off"}."
            is LocalIntent.OpenSettings -> "Opened ${intent.page.replace('_', ' ')} settings."
            is LocalIntent.Volume -> "${intent.stream.replaceFirstChar { it.uppercase() }} volume set to ${intent.percent}%."
            is LocalIntent.Media -> "Sent ${intent.action.replace('_', ' ')} to the media player."
            is LocalIntent.Timer -> "Timer set for ${duration(intent.seconds)}."
        }
    }

    /** Tool failures are worded for the model ("Ask the user to..."); say them to the user directly. */
    fun failure(text: String): String =
        text.removePrefix("FAILED: ").lineSequence().firstOrNull().orEmpty().replace("Ask the user to ", "Please ").ifBlank { "That didn't work." }

    private fun notifications(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.startsWith("[") }.toList()
        if (lines.isEmpty()) return if (text.contains("No notifications", true)) "You have no notifications." else "I couldn't read your notifications."
        val shown = lines.take(3).map { line ->
            line.substringBefore(" key=").replace(Regex("\\s*\\((\\d{1,2}:\\d{2})[^)]*\\)\\s*$"), "").take(140)
        }
        val more = if (lines.size > 3) " And ${lines.size - 3} more." else ""
        return "You have ${lines.size} notification${if (lines.size == 1) "" else "s"}. ${shown.joinToString(" ")}$more"
    }

    fun duration(seconds: Int): String {
        val h = seconds / 3600; val m = seconds % 3600 / 60; val s = seconds % 60
        return listOfNotNull(
            h.takeIf { it > 0 }?.let { "$it hour${if (it == 1) "" else "s"}" },
            m.takeIf { it > 0 }?.let { "$it minute${if (it == 1) "" else "s"}" },
            s.takeIf { it > 0 }?.let { "$it second${if (it == 1) "" else "s"}" },
        ).joinToString(" ")
    }

    private fun obj(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull
    private fun onOff(v: String?) = if (v == "true") "on" else "off"
}
