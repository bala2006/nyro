package com.nyro.companion.tools

import android.app.SearchManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.NyroNotificationListener
import com.nyro.companion.data.MemoryEntity
import com.nyro.companion.data.Redactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ctx get() = NyroApp.graph.context
private val g get() = NyroApp.graph

private fun startIntent(i: Intent): Boolean = runCatching {
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
}.getOrDefault(false)

/** After launching something, confirm through accessibility that the expected app is in front. */
private suspend fun verifyForeground(expectPkg: String?, timeoutMs: Long = 4500): String {
    val svc = NyroAccessibilityService.current ?: return "Launch intent sent (cannot verify: accessibility is off)."
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        delay(250)
        val snap = ScreenSession.read(svc)
        if (expectPkg == null || snap.packageName == expectPkg) {
            svc.waitForIdle(quietMs = 300, timeoutMs = 2000)
            return "Verified: now showing ${g.apps.label(ScreenSession.read(svc).packageName)}.\n" + ScreenSession.last!!.render(80)
        }
    }
    val now = ScreenSession.read(svc)
    return "Not verified: foreground is still ${now.appLabel.ifBlank { now.packageName }}.\n" + now.render(50)
}

fun deviceTools(): List<Tool> = listOf(
    object : Tool("get_device_info", "Device hardware/software: manufacturer, model, Android version, CPU, RAM, storage, display.") {
        override fun describe(args: Args) = "Reading device information"
        override suspend fun run(args: Args) = ToolResult(g.device.device().toString())
    },
    object : Tool("get_battery", "Battery percent, charging state, temperature, voltage, health, power-save and thermal status.") {
        override fun describe(args: Args) = "Reading battery"
        override suspend fun run(args: Args) = ToolResult(g.device.battery().toString())
    },
    object : Tool("get_network_status", "Connectivity: transport, internet validation, Wi-Fi/Bluetooth enabled, metered, ringer mode.") {
        override fun describe(args: Args) = "Checking connectivity"
        override suspend fun run(args: Args) = ToolResult(g.device.network().toString())
    },
    object : Tool("list_sensors", "List hardware sensors available on this phone.") {
        override fun describe(args: Args) = "Listing sensors"
        override suspend fun run(args: Args) = ToolResult(g.device.sensors().toString())
    },
    object : Tool("read_sensor", "Take one live reading from a sensor.", params = {
        enum("sensor", "Sensor", listOf("accelerometer", "gyroscope", "magnetic_field", "light", "proximity", "pressure", "gravity", "linear_acceleration", "rotation_vector", "ambient_temperature", "relative_humidity", "step_counter"))
    }) {
        override fun describe(args: Args) = "Reading ${args.str("sensor")} sensor"
        override suspend fun run(args: Args) = ToolResult(g.device.readSensor(args.str("sensor")!!).toString())
    },
    object : Tool("set_flashlight", "Turn the flashlight (torch) on or off.", params = { bool("on", "true to turn on", required = true) }) {
        override fun describe(args: Args) = if (args.bool("on") == true) "Turning flashlight on" else "Turning flashlight off"
        override suspend fun run(args: Args): ToolResult {
            val id = g.device.hasFlashlight() ?: return ToolResult.fail("No flashlight on this device.")
            return runCatching {
                ctx.getSystemService(CameraManager::class.java).setTorchMode(id, args.bool("on") == true)
                ToolResult("Flashlight ${if (args.bool("on") == true) "on" else "off"}.")
            }.getOrElse { ToolResult.fail("Camera is in use or torch unavailable: ${it.message}") }
        }
    },
    object : Tool("set_volume", "Set a volume stream level in percent.", params = {
        enum("stream", "Which volume", listOf("media", "ring", "alarm", "notification", "call")); int("percent", "0-100")
    }) {
        override fun describe(args: Args) = "Setting ${args.str("stream")} volume to ${args.int("percent")}%"
        override suspend fun run(args: Args): ToolResult {
            val am = ctx.getSystemService(AudioManager::class.java)
            val s = when (args.str("stream")) { "ring" -> AudioManager.STREAM_RING; "alarm" -> AudioManager.STREAM_ALARM; "notification" -> AudioManager.STREAM_NOTIFICATION; "call" -> AudioManager.STREAM_VOICE_CALL; else -> AudioManager.STREAM_MUSIC }
            val max = am.getStreamMaxVolume(s); val min = am.getStreamMinVolume(s)
            val target = (min + (max - min) * (args.int("percent")!!.coerceIn(0, 100)) / 100.0).toInt()
            return runCatching {
                am.setStreamVolume(s, target, AudioManager.FLAG_SHOW_UI)
                ToolResult("Volume set: ${am.getStreamVolume(s)}/$max.")
            }.getOrElse { ToolResult.fail("Volume change blocked (Do Not Disturb may restrict it).") }
        }
    },
    object : Tool("media_control", "Control the active media player.", params = { enum("action", "Action", listOf("play_pause", "play", "pause", "next", "previous")) }) {
        override fun describe(args: Args) = "Media: ${args.str("action")}"
        override suspend fun run(args: Args): ToolResult {
            val am = ctx.getSystemService(AudioManager::class.java)
            val code = when (args.str("action")) { "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE; "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code)); am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
            delay(400)
            return ToolResult("Media key sent. Music active now: ${am.isMusicActive}.")
        }
    },
    object : Tool("copy_to_clipboard", "Copy text to the clipboard.", params = { string("text", "Text") }) {
        override fun describe(args: Args) = "Copying to clipboard"
        override suspend fun run(args: Args): ToolResult = withContext(Dispatchers.Main) {
            ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("NYRO", args.str("text")))
            ToolResult("Copied.")
        }
    },
)

private val settingsPages = mapOf(
    "main" to Settings.ACTION_SETTINGS, "wifi" to Settings.ACTION_WIFI_SETTINGS, "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
    "internet_panel" to Settings.Panel.ACTION_INTERNET_CONNECTIVITY, "volume_panel" to Settings.Panel.ACTION_VOLUME,
    "mobile_data" to Settings.ACTION_DATA_ROAMING_SETTINGS, "airplane" to Settings.ACTION_AIRPLANE_MODE_SETTINGS,
    "display" to Settings.ACTION_DISPLAY_SETTINGS, "sound" to Settings.ACTION_SOUND_SETTINGS, "battery" to Intent.ACTION_POWER_USAGE_SUMMARY,
    "battery_saver" to Settings.ACTION_BATTERY_SAVER_SETTINGS, "apps" to Settings.ACTION_APPLICATION_SETTINGS,
    "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS, "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
    "security" to Settings.ACTION_SECURITY_SETTINGS, "privacy" to Settings.ACTION_PRIVACY_SETTINGS, "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
    "date_time" to Settings.ACTION_DATE_SETTINGS, "language" to Settings.ACTION_LOCALE_SETTINGS, "storage" to Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
    "developer" to Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "about" to Settings.ACTION_DEVICE_INFO_SETTINGS, "nfc" to Settings.ACTION_NFC_SETTINGS,
    "hotspot" to "android.settings.TETHER_SETTINGS", "dnd" to Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS, "wallpaper" to Intent.ACTION_SET_WALLPAPER,
)

fun appTools(): List<Tool> = listOf(
    object : Tool("list_apps", "List installed launchable apps, optionally filtered by name.", params = { string("query", "Filter text", required = false) }) {
        override fun describe(args: Args) = "Listing apps"
        override suspend fun run(args: Args): ToolResult {
            val list = g.apps.search(args.str("query").orEmpty())
            return ToolResult(if (list.isEmpty()) "No matching apps." else list.take(150).joinToString("\n") { "${it.label} (${it.pkg})" })
        }
    },
    object : Tool("open_app", "Open an installed app by name or package and verify it is in the foreground.", params = { string("name", "App name, e.g. 'YouTube', or package name") }) {
        override fun describe(args: Args) = "Opening ${args.str("name")}"
        override suspend fun run(args: Args): ToolResult {
            val app = g.apps.find(args.str("name")!!) ?: return ToolResult.fail("No installed app matches \"${args.str("name")}\". Use list_apps to see options.")
            if (!g.apps.launch(app.pkg)) return ToolResult.fail("${app.label} could not be launched.")
            return ToolResult("Launched ${app.label} (${app.pkg}). " + verifyForeground(app.pkg))
        }
    },
    object : Tool("open_settings", "Open a system settings screen. Apps cannot toggle Wi-Fi/Bluetooth/mobile data directly on modern Android; open the page or panel and tap the toggle.", params = {
        enum("page", "Settings page", settingsPages.keys.toList() + "app_details"); string("package", "Package for app_details", required = false)
    }) {
        override fun describe(args: Args) = "Opening ${args.str("page")} settings"
        override suspend fun run(args: Args): ToolResult {
            val page = args.str("page")!!
            val intent = if (page == "app_details") {
                val pkg = args.str("package")?.let { g.apps.find(it)?.pkg ?: it } ?: return ToolResult.fail("package is required for app_details")
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
            } else Intent(settingsPages[page]!!)
            if (!startIntent(intent) && !startIntent(Intent(Settings.ACTION_SETTINGS))) return ToolResult.fail("This settings page is not available on this device.")
            return ToolResult("Opened $page settings. " + verifyForeground(null, 1500))
        }
    },
    object : Tool("open_url", "Open a web URL in the default browser or the app that handles it.", params = { string("url", "https URL") }) {
        override fun describe(args: Args) = "Opening ${args.str("url")}"
        override suspend fun run(args: Args): ToolResult {
            val raw = args.str("url")!!.trim()
            val url = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
            val uri = Uri.parse(url)
            if (uri.host.isNullOrBlank()) return ToolResult.fail("Invalid URL.")
            if (!startIntent(Intent(Intent.ACTION_VIEW, uri))) return ToolResult.fail("No app can open this URL.")
            return ToolResult("Opened $url. " + verifyForeground(null, 1500))
        }
    },
    object : Tool("web_search", "Search the web with the default search app/browser.", params = { string("query", "Search terms") }) {
        override fun describe(args: Args) = "Searching the web for \"${args.str("query")}\""
        override suspend fun run(args: Args): ToolResult {
            val q = args.str("query")!!
            val ok = startIntent(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, q)) ||
                startIntent(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q))))
            if (!ok) return ToolResult.fail("No browser available.")
            return ToolResult("Search opened. " + verifyForeground(null, 1500))
        }
    },
    object : Tool("set_alarm", "Create an alarm in the clock app.", params = { int("hour", "0-23"); int("minute", "0-59"); string("label", "Label", required = false) }) {
        override fun describe(args: Args) = "Setting alarm %02d:%02d".format(args.int("hour"), args.int("minute"))
        override suspend fun run(args: Args): ToolResult {
            val h = args.int("hour")!!; val m = args.int("minute")!!
            if (h !in 0..23 || m !in 0..59) return ToolResult.fail("Invalid time.")
            val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true).apply { args.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            return if (startIntent(i)) ToolResult("Alarm request sent to the clock app for %02d:%02d.".format(h, m)) else ToolResult.fail("No clock app accepts alarms.")
        }
    },
    object : Tool("set_timer", "Start a countdown timer in the clock app.", params = { int("seconds", "Duration in seconds"); string("label", "Label", required = false) }) {
        override fun describe(args: Args) = "Starting a ${args.int("seconds")}s timer"
        override suspend fun run(args: Args): ToolResult {
            val s = args.int("seconds")!!
            if (s !in 1..86_399) return ToolResult.fail("Timer must be 1 second to 24 hours.")
            val i = Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                .apply { args.str("label")?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) } }
            return if (startIntent(i)) ToolResult("Timer request sent for $s seconds.") else ToolResult.fail("No clock app accepts timers.")
        }
    },
    object : Tool("open_dialer", "Open the phone dialer with a number filled in. The user presses call.", params = { string("number", "Phone number") }) {
        override fun describe(args: Args) = "Opening dialer for ${args.str("number")}"
        override suspend fun run(args: Args): ToolResult {
            val n = args.str("number")!!.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
            return if (startIntent(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(n))))) ToolResult("Dialer opened with $n. The user must press call (or ask NYRO to tap it).") else ToolResult.fail("No dialer.")
        }
    },
    object : Tool("compose_message", "Open the SMS app with a recipient and message pre-filled. Nothing is sent until Send is tapped.", params = { string("number", "Recipient number"); string("text", "Message body") }) {
        override fun describe(args: Args) = "Drafting a message to ${args.str("number")}"
        override suspend fun run(args: Args): ToolResult {
            val i = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(args.str("number")!!))).putExtra("sms_body", args.str("text"))
            return if (startIntent(i)) ToolResult("Message drafted in the SMS app. " + verifyForeground(null, 1500)) else ToolResult.fail("No SMS app.")
        }
    },
    object : Tool("share_text", "Open the Android share sheet with text.", baseRisk = Risk.MEDIUM, params = { string("text", "Text to share") }) {
        override fun describe(args: Args) = "Sharing text"
        override suspend fun run(args: Args): ToolResult {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, args.str("text"))
            return if (startIntent(Intent.createChooser(send, "Share via"))) ToolResult("Share sheet opened.") else ToolResult.fail("Sharing unavailable.")
        }
    },
)

fun memoryTools(): List<Tool> = listOf(
    object : Tool("remember", "Save a durable fact or preference about the user to local memory (on this phone only). Never store passwords, OTPs, PINs or banking details.", params = { string("fact", "The fact, in one sentence") }) {
        override fun describe(args: Args) = "Remembering"
        override suspend fun run(args: Args): ToolResult {
            val f = args.str("fact")!!.trim()
            if (Redactor.containsSecret(f)) return ToolResult.fail("Refused: this looks like sensitive authentication or payment data, which NYRO never stores.")
            val id = g.dao.insertMemory(MemoryEntity(content = f.take(500), source = "assistant"))
            return ToolResult("Saved memory #$id.")
        }
    },
    object : Tool("recall", "Search local memories.", params = { string("query", "Keywords", required = false) }) {
        override fun describe(args: Args) = "Searching memory"
        override suspend fun run(args: Args): ToolResult {
            val words = args.str("query")?.lowercase()?.split(Regex("\\W+"))?.filter { it.length > 2 }.orEmpty()
            val all = g.dao.memoryList(300)
            val hits = if (words.isEmpty()) all else all.filter { m -> words.any { m.content.lowercase().contains(it) } }
            return ToolResult(if (hits.isEmpty()) "No matching memories." else hits.take(30).joinToString("\n") { "#${it.id}: ${it.content}" })
        }
    },
    object : Tool("forget", "Delete a memory by id.", baseRisk = Risk.MEDIUM, params = { int("memory_id", "Memory id") }) {
        override fun describe(args: Args) = "Deleting memory #${args.int("memory_id")}"
        override suspend fun run(args: Args) = if (g.dao.deleteMemory(args.int("memory_id")!!.toLong()) > 0) ToolResult("Deleted.") else ToolResult.fail("No such memory.")
    },
)

fun fileTools(): List<Tool> = listOf(
    object : Tool("search_files", "Search photos, videos, audio and downloads the user allowed NYRO to read. Returns name, type, size, date and a content uri.", requires = setOf(Requirement.MEDIA), params = {
        string("query", "Part of the file name, or empty for most recent", required = false)
        enum("kind", "File kind", listOf("any", "image", "video", "audio"), required = false)
    }) {
        override fun describe(args: Args) = "Searching files${args.str("query")?.let { " for \"$it\"" } ?: ""}"
        override suspend fun run(args: Args): ToolResult = withContext(Dispatchers.IO) {
            val kind = args.str("kind") ?: "any"
            val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
            val sel = StringBuilder("${MediaStore.MediaColumns.SIZE} > 0")
            val selArgs = mutableListOf<String>()
            args.str("query")?.let { sel.append(" AND ${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"); selArgs += "%$it%" }
            when (kind) {
                "image" -> sel.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE}")
                "video" -> sel.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO}")
                "audio" -> sel.append(" AND ${MediaStore.Files.FileColumns.MEDIA_TYPE}=${MediaStore.Files.FileColumns.MEDIA_TYPE_AUDIO}")
            }
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH)
            val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            val rows = mutableListOf<String>()
            ctx.contentResolver.query(uri, proj, sel.toString(), selArgs.toTypedArray(), "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && rows.size < 40) {
                    val id = c.getLong(0)
                    rows += "${c.getString(1)} · ${c.getString(2) ?: "?"} · ${c.getLong(3) / 1024} KB · ${fmt.format(Date(c.getLong(4) * 1000))} · ${c.getString(5) ?: ""} · uri=${ContentUris.withAppendedId(uri, id)}"
                }
            }
            ToolResult(if (rows.isEmpty()) "No matching files that NYRO is allowed to read." else rows.joinToString("\n"))
        }
    },
    object : Tool("open_file", "Open a file (content uri from search_files) in a suitable app.", requires = setOf(Requirement.MEDIA), params = { string("uri", "content:// uri") }) {
        override fun describe(args: Args) = "Opening file"
        override suspend fun run(args: Args): ToolResult {
            val uri = Uri.parse(args.str("uri")!!)
            if (uri.scheme != "content") return ToolResult.fail("Only content:// uris from search_files are allowed.")
            val type = ctx.contentResolver.getType(uri) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return if (startIntent(i)) ToolResult("Opened file. " + verifyForeground(null, 1500)) else ToolResult.fail("No app can open $type.")
        }
    },
    object : Tool("share_file", "Share a file through the Android share sheet.", baseRisk = Risk.MEDIUM, requires = setOf(Requirement.MEDIA), params = { string("uri", "content:// uri") }) {
        override fun describe(args: Args) = "Sharing a file"
        override suspend fun run(args: Args): ToolResult {
            val uri = Uri.parse(args.str("uri")!!)
            if (uri.scheme != "content") return ToolResult.fail("Only content:// uris from search_files are allowed.")
            val send = Intent(Intent.ACTION_SEND).setType(ctx.contentResolver.getType(uri) ?: "*/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            return if (startIntent(Intent.createChooser(send, "Share via").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) ToolResult("Share sheet opened.") else ToolResult.fail("Sharing unavailable.")
        }
    },
)

fun notificationTools(): List<Tool> = listOf(
    object : Tool("get_notifications", "Read current notifications (app, title, text, key). Requires notification access.", requires = setOf(Requirement.NOTIFICATIONS)) {
        override fun describe(args: Args) = "Reading notifications"
        override suspend fun run(args: Args): ToolResult {
            val l = NyroNotificationListener.instance ?: return ToolResult.fail(Requirement.NOTIFICATIONS.label)
            val items = l.items(ctx)
            val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
            return ToolResult(if (items.isEmpty()) "No notifications." else items.take(40).joinToString("\n") {
                "[${it.app}] ${it.title}: ${Redactor.redact(it.text).take(200)} (${fmt.format(Date(it.time))}${if (it.canReply) ", can reply" else ""}) key=${it.key}"
            })
        }
    },
    object : Tool("reply_to_notification", "Send a reply through a notification's reply action (e.g. a chat message).", baseRisk = Risk.MEDIUM, requires = setOf(Requirement.NOTIFICATIONS), params = {
        string("key", "Notification key from get_notifications"); string("message", "Reply text")
    }) {
        override fun describe(args: Args) = "Replying: \"${args.str("message")?.take(60)}\""
        override suspend fun run(args: Args): ToolResult {
            val l = NyroNotificationListener.instance ?: return ToolResult.fail(Requirement.NOTIFICATIONS.label)
            return ToolResult(l.reply(ctx, args.str("key")!!, args.str("message")!!))
        }
    },
    object : Tool("open_notification", "Open the app/screen behind a notification.", requires = setOf(Requirement.NOTIFICATIONS), params = { string("key", "Notification key") }) {
        override fun describe(args: Args) = "Opening notification"
        override suspend fun run(args: Args): ToolResult {
            val l = NyroNotificationListener.instance ?: return ToolResult.fail(Requirement.NOTIFICATIONS.label)
            return if (l.open(args.str("key")!!)) ToolResult("Opened. " + verifyForeground(null, 1500)) else ToolResult.fail("Notification has no open action or was dismissed.")
        }
    },
)

fun hasMediaPermission(): Boolean = listOf(
    android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_AUDIO,
).any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
