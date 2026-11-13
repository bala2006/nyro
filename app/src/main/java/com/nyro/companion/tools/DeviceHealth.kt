package com.nyro.companion.tools

import android.content.ContentUris
import android.provider.MediaStore
import android.provider.Settings
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.NyroNotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.Locale

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

/** Read-only device tools: health findings with suggestions, and a list of the largest media files. */
fun healthTools(): List<Tool> = listOf(
    object : Tool("device_health_check", "Check the phone's health: battery level and temperature, thermal state, memory, storage, internet connection and whether NYRO's permissions are on. Returns findings and suggestions. Read-only.") {
        override fun describe(args: Args) = "Checking phone health"
        override suspend fun run(args: Args): ToolResult = ToolResult(healthReport())
    },
    object : Tool(
        "find_large_files",
        "List the largest photos, videos and audio files, to help decide what takes space. Read-only: deleting is not available.",
        requires = setOf(Requirement.MEDIA),
        params = { int("min_mb", "Only files at least this big in MB. Default 100.", required = false) },
    ) {
        override fun describe(args: Args) = "Finding large files"
        override suspend fun run(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val minBytes = (args.int("min_mb") ?: 100).coerceAtLeast(1).toLong() * 1024 * 1024
            val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.RELATIVE_PATH)
            val rows = mutableListOf<String>()
            NyroApp.graph.context.contentResolver.query(
                uri, proj, "${MediaStore.MediaColumns.SIZE} >= ?", arrayOf(minBytes.toString()),
                "${MediaStore.MediaColumns.SIZE} DESC",
            )?.use { c ->
                while (c.moveToNext() && rows.size < 25) {
                    val mb = c.getLong(2) / (1024.0 * 1024.0)
                    rows += "%.0f MB · %s · %s".format(Locale.US, mb, c.getString(1), c.getString(3) ?: "")
                }
            }
            ToolResult(if (rows.isEmpty()) "No files of ${minBytes / (1024 * 1024)} MB or more were found." else rows.joinToString("\n"))
        }
    },
)

/** Rule-based findings. Each finding names the number and the next step; nothing is changed. */
fun healthReport(): String {
    val g = NyroApp.graph
    val findings = mutableListOf<String>()
    val b = g.device.battery()
    val m = g.device.memory()
    val st = g.device.storage()
    val n = g.device.network()

    b.s("percent")?.toIntOrNull()?.let { p ->
        if (p < 20 && b.s("status") != "charging") findings += "Battery is low ($p%). Plug in, or turn on battery saver."
    }
    b.s("temperature_c")?.toDoubleOrNull()?.let { t ->
        if (t >= 40.0) findings += "Battery temperature is high (${"%.1f".format(t)} °C). Close heavy apps and let the phone cool."
    }
    b.s("thermal_status")?.let { t ->
        if (t in listOf("moderate", "severe", "critical")) findings += "Thermal state is $t. The phone may slow down until it cools."
    }
    if (b.s("power_save_mode") == "true") findings += "Battery saver is on, which limits background work."
    m.s("used_percent")?.toIntOrNull()?.let { u ->
        if (u >= 85) findings += "Memory is almost full ($u% used). Close background apps."
    }
    st.s("used_percent")?.toIntOrNull()?.let { u ->
        if (u >= 90) findings += "Storage is almost full ($u% used). Use find_large_files to see what takes space."
    }
    if (n.s("connected") != "true") findings += "No network connection."
    else if (n.s("validated_internet") != "true") findings += "Connected to a network, but the internet is not reachable. Check Wi-Fi or mobile data."
    if (NyroAccessibilityService.current == null) findings += "NYRO phone control is off, so screen actions will fail. Enable it in Permissions."
    if (!Settings.canDrawOverlays(g.context)) findings += "Display over other apps is off, so floating NYRO won't show."
    if (NyroNotificationListener.instance == null) findings += "Notification access is off. Summaries of notifications won't work."

    val header = "Battery ${b.s("percent") ?: "?"}% (${b.s("status") ?: "?"}), memory ${m.s("used_percent") ?: "?"}% used, storage ${st.s("used_percent") ?: "?"}% used, network ${n.s("transport") ?: "?"}."
    return if (findings.isEmpty()) "$header No problems found." else header + "\n" + findings.joinToString("\n") { "- $it" }
}
