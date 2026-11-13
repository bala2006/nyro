package com.nyro.companion.agent.router

import com.nyro.companion.device.AppInfo
import com.nyro.companion.device.AppMatch
import com.nyro.companion.perf.Clock
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf

/** A request NYRO can fully resolve on the phone, with validated arguments. */
sealed interface LocalIntent {
    data class OpenApp(val app: AppInfo) : LocalIntent
    data class FindApp(val query: String, val match: AppMatch) : LocalIntent
    data object Battery : LocalIntent
    data object Storage : LocalIntent
    data object Memory : LocalIntent
    data object Network : LocalIntent
    data object DeviceInfo : LocalIntent
    data object Sensors : LocalIntent
    data object Notifications : LocalIntent
    data class Time(val time: Boolean, val date: Boolean) : LocalIntent
    data object Home : LocalIntent
    data object Back : LocalIntent
    data object Recents : LocalIntent
    data object NotificationShade : LocalIntent
    data object QuickSettings : LocalIntent
    data object LockScreen : LocalIntent
    data class Flashlight(val on: Boolean) : LocalIntent
    data class OpenSettings(val page: String) : LocalIntent
    data class Volume(val stream: String, val percent: Int) : LocalIntent
    data class Media(val action: String) : LocalIntent
    data class Timer(val seconds: Int) : LocalIntent

    /** Read-only intents may be answered together in one reply. */
    val readOnly: Boolean
        get() = when (this) {
            is FindApp, Battery, Storage, Memory, Network, DeviceInfo, Sensors, Notifications, is Time -> true
            else -> false
        }
}

/** A tool invocation derived from an intent. Runs through the same validation, permission and risk pipeline as model calls. */
data class LocalCall(val tool: String, val args: Map<String, Any?> = emptyMap())

sealed interface RouteDecision {
    data class Local(val intents: List<LocalIntent>) : RouteDecision
    /** Hand the request to Azure. [reason] is for logs and tests only. */
    data class Escalate(val reason: String) : RouteDecision
}

fun LocalIntent.calls(): List<LocalCall> = when (this) {
    is LocalIntent.OpenApp -> listOf(LocalCall("open_app", mapOf("name" to app.pkg, "brief" to true)))
    is LocalIntent.FindApp, is LocalIntent.Time -> emptyList()
    LocalIntent.Battery -> listOf(LocalCall("get_battery"))
    LocalIntent.Storage, LocalIntent.Memory, LocalIntent.DeviceInfo -> listOf(LocalCall("get_device_info"))
    LocalIntent.Network -> listOf(LocalCall("get_network_status"))
    LocalIntent.Sensors -> listOf(LocalCall("list_sensors"))
    LocalIntent.Notifications -> listOf(LocalCall("get_notifications"))
    LocalIntent.Home -> listOf(LocalCall("press_home"))
    LocalIntent.Back -> listOf(LocalCall("press_back"))
    LocalIntent.Recents -> listOf(LocalCall("open_recents"))
    LocalIntent.NotificationShade -> listOf(LocalCall("open_notification_shade"))
    LocalIntent.QuickSettings -> listOf(LocalCall("open_quick_settings"))
    LocalIntent.LockScreen -> listOf(LocalCall("lock_screen"))
    is LocalIntent.Flashlight -> listOf(LocalCall("set_flashlight", mapOf("on" to on)))
    is LocalIntent.OpenSettings -> listOf(LocalCall("open_settings", mapOf("page" to page)))
    is LocalIntent.Volume -> listOf(LocalCall("set_volume", mapOf("stream" to stream, "percent" to percent)))
    is LocalIntent.Media -> listOf(LocalCall("media_control", mapOf("action" to action)))
    is LocalIntent.Timer -> listOf(LocalCall("set_timer", mapOf("seconds" to seconds)))
}

fun interface AppLookup {
    fun resolve(query: String): AppMatch
}

/**
 * Deterministic first stop for every request. It never guesses:
 *
 * 1. Tokenise and drop politeness ("please", "can you", "hey nyro").
 * 2. Find which intent the request is about (its head words).
 * 3. Require that EVERY remaining word belongs to that intent's closed vocabulary. An unknown word means the user
 *    asked for something more than the router understands, so the request goes to Azure.
 * 4. Validate slots (a unique installed app, a known settings page, a volume 0..100, a timer 1 s..24 h).
 *
 * "How do I improve battery life" and "battery saver" contain words outside the battery vocabulary, so they escalate;
 * "what's my battery" does not. Ambiguity (two apps with the same name, an on/off that was not said) also escalates.
 */
class FastRouter(private val apps: AppLookup, private val clock: Clock) {

    fun route(raw: String): RouteDecision {
        val t0 = clock.now()
        try {
            return decide(raw)
        } finally {
            Perf.record(Metric.ROUTE, clock.now() - t0)
        }
    }

    private class Rule(
        val name: String,
        val heads: (List<String>) -> Boolean,
        val vocab: Set<String>,
        val info: Boolean = false,
        /** When this rule matches, other matching rules are ignored ("wifi settings" is a settings page, not a network question). */
        val exclusive: Boolean = false,
        val build: (List<String>) -> LocalIntent?,
    )

    private fun decide(raw: String): RouteDecision {
        if (raw.isBlank()) return RouteDecision.Escalate("empty")
        if (raw.length > MAX_CHARS) return RouteDecision.Escalate("too long")
        if (raw.any { it.isLetter() && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN }) return RouteDecision.Escalate("non-latin script")

        var t = tokenize(raw)
        if (t.isEmpty() || t.size > MAX_TOKENS) return RouteDecision.Escalate("empty or too many words")
        if (t.size >= 3 && t[0] in FIND_VERBS && t[1] in JOINERS && t[2] in OPEN_VERBS) t = t.drop(2) // "find and open X" is "open X"

        val matched = rules.filter { it.heads(t) }
        matched.firstOrNull { it.exclusive }?.let { return finish(it, t) }
        if (matched.size == 1) return finish(matched[0], t)
        if (matched.size > 1) return multi(matched, t)

        openApp(t)?.let { return it }
        findApp(t)?.let { return it }
        return RouteDecision.Escalate("no intent")
    }

    private fun finish(rule: Rule, t: List<String>): RouteDecision {
        val stray = t.firstOrNull { it !in rule.vocab && !it.all(Char::isDigit) }
        if (stray != null) return RouteDecision.Escalate("${rule.name}: unknown word \"$stray\"")
        val intent = rule.build(t) ?: return RouteDecision.Escalate("${rule.name}: invalid or incomplete slots")
        return RouteDecision.Local(listOf(intent))
    }

    /** "battery and storage": several read-only questions in one request. Anything else mixed escalates. */
    private fun multi(rs: List<Rule>, t: List<String>): RouteDecision {
        if (!rs.all { it.info }) return RouteDecision.Escalate("mixed intents: ${rs.joinToString { it.name }}")
        val vocab = rs.flatMapTo(HashSet()) { it.vocab } + JOINERS
        val stray = t.firstOrNull { it !in vocab }
        if (stray != null) return RouteDecision.Escalate("mixed read-only intents: unknown word \"$stray\"")
        val intents = rs.sortedBy { r -> t.indexOfFirst { w -> r.heads(listOf(w)) }.let { if (it < 0) Int.MAX_VALUE else it } }
            .mapNotNull { it.build(t) }.distinct()
        return if (intents.size == rs.size) RouteDecision.Local(intents) else RouteDecision.Escalate("mixed intents: invalid slots")
    }

    // ---------------- Rules ----------------

    private val rules: List<Rule> by lazy {
        listOf(
            Rule("quick_settings", { "quick" in it && ("settings" in it || "panel" in it || "tiles" in it) },
                setOf("open", "show", "the", "quick", "settings", "panel", "tiles", "pull", "down", "my"), exclusive = true) { LocalIntent.QuickSettings },

            Rule("notification_shade", { t -> t.any { it in setOf("shade", "panel", "drawer", "tray") } && t.any { it.startsWith("notification") } },
                setOf("open", "pull", "down", "show", "the", "notification", "notifications", "shade", "panel", "drawer", "tray", "my"), exclusive = true) { LocalIntent.NotificationShade },

            Rule("notifications", { t -> t.any { it == "notifications" || it == "notification" || it == "notifs" } && t.none { it in setOf("shade", "panel", "drawer", "tray", "settings", "setting") } },
                setOf("read", "show", "check", "list", "what", "whats", "are", "my", "the", "new", "unread", "any", "do", "i", "have", "latest", "recent", "tell", "me", "get", "give",
                    "current", "pending", "notifications", "notification", "notifs", "out", "loud", "aloud", "open", "is", "there"), info = true) { t ->
                if ("open" in t && t.none { it in setOf("read", "what", "whats", "any", "new", "unread", "latest", "recent", "check") }) LocalIntent.NotificationShade
                else LocalIntent.Notifications
            },

            Rule("settings", { t -> ("settings" in t || "setting" in t) && "quick" !in t },
                SETTINGS_VOCAB + SETTINGS_ALIAS_WORDS, exclusive = true) { t -> settingsPage(t)?.let { LocalIntent.OpenSettings(it) } },

            Rule("battery", { t -> t.any { it in setOf("battery", "charging", "charge") } },
                setOf("what", "whats", "is", "the", "my", "current", "level", "percent", "percentage", "how", "much", "left", "remaining", "status", "check", "show", "tell", "me", "get", "read",
                    "give", "of", "on", "phone", "device", "battery", "charge", "charging", "are", "you", "i", "have", "do", "it", "now", "right", "at", "a", "power"), info = true) { LocalIntent.Battery },

            Rule("storage", { t -> t.any { it in setOf("storage", "disk") } || ("space" in t && t.any { it in setOf("free", "left", "available", "storage", "much") }) },
                setOf("what", "whats", "how", "much", "free", "left", "available", "remaining", "used", "is", "the", "my", "on", "phone", "device", "internal", "storage", "space", "disk", "check",
                    "show", "tell", "me", "get", "read", "give", "of", "do", "i", "have", "are", "there", "gb", "total", "capacity"), info = true) { LocalIntent.Storage },

            Rule("memory", { t -> "ram" in t || ("memory" in t && t.any { it in setOf("usage", "used", "free", "available", "much") }) },
                setOf("what", "whats", "how", "much", "free", "available", "used", "is", "the", "my", "ram", "memory", "on", "phone", "device", "check", "show", "tell", "me", "get", "read", "give",
                    "of", "left", "usage", "total", "do", "i", "have"), info = true) { LocalIntent.Memory },

            Rule("network", { t -> t.any { it in setOf("wifi", "internet", "network", "connection", "bluetooth", "online") } },
                setOf("what", "whats", "is", "the", "my", "wifi", "internet", "network", "connection", "bluetooth", "on", "off", "connected", "online", "status", "check", "show", "tell", "me", "get",
                    "are", "am", "i", "do", "have", "working", "to", "state"), info = true) { t ->
                if (t.any { it in setOf("turn", "switch", "enable", "disable") }) null else LocalIntent.Network
            },

            Rule("device_info", { t -> t.any { it in setOf("specs", "specifications", "processor", "cpu", "chipset", "soc") } || hasPair(t, "android", "version") || hasPair(t, "device", "info") ||
                hasPair(t, "phone", "info") || hasPair(t, "phone", "model") || hasPair(t, "device", "model") || hasPair(t, "which", "phone") || hasPair(t, "what", "phone") },
                setOf("what", "whats", "which", "is", "the", "my", "this", "phone", "device", "info", "information", "about", "specs", "specifications", "model", "processor", "cpu", "chipset", "soc",
                    "android", "version", "tell", "me", "show", "check", "get", "have", "do", "i", "are", "cores", "many", "os", "of", "on", "running"), info = true) { LocalIntent.DeviceInfo },

            Rule("sensors", { t -> "sensors" in t || "sensor" in t },
                setOf("list", "what", "whats", "which", "are", "the", "my", "on", "phone", "device", "sensors", "sensor", "available", "show", "tell", "me", "get", "have", "do", "i", "how", "many"), info = true) { LocalIntent.Sensors },

            Rule("time", { t -> "time" in t || "date" in t || ("day" in t && "today" in t) || (t.size <= 4 && "day" in t && "what" in t) },
                setOf("what", "whats", "is", "the", "current", "time", "date", "day", "today", "todays", "it", "now", "right", "tell", "me", "do", "you", "have", "a", "of", "in", "on"), info = true) { t ->
                LocalIntent.Time(time = "time" in t, date = "date" in t || "day" in t || "todays" in t)
            },

            Rule("timer", { t -> "timer" in t },
                setOf("set", "start", "a", "an", "the", "timer", "for", "of", "minute", "minutes", "min", "mins", "second", "seconds", "sec", "secs", "hour", "hours", "hr", "hrs", "and", "my", "please", "countdown",
                    "create", "new", "run")) { t -> timerSeconds(t)?.let { LocalIntent.Timer(it) } },

            Rule("flashlight", { t -> t.any { it in setOf("flashlight", "torch", "flash") } },
                setOf("turn", "switch", "on", "off", "the", "flashlight", "torch", "flash", "light", "my", "phone", "enable", "disable", "put", "set", "to", "activate", "deactivate", "up", "it")) { t ->
                val off = t.any { it in setOf("off", "disable", "deactivate") }
                val on = t.any { it in setOf("on", "enable", "activate") }
                when { on == off -> null; else -> LocalIntent.Flashlight(on = on) }
            },

            Rule("volume", { t -> "volume" in t },
                setOf("set", "the", "media", "music", "ring", "ringtone", "alarm", "notification", "notifications", "call", "volume", "to", "at", "percent", "my", "phone", "change", "make", "it")) { t ->
                val n = t.filter { it.all(Char::isDigit) }.singleOrNull()?.toIntOrNull()
                if (n == null || n !in 0..100) null
                else LocalIntent.Volume(when { "ring" in t || "ringtone" in t -> "ring"; "alarm" in t -> "alarm"; "notification" in t || "notifications" in t -> "notification"; "call" in t -> "call"; else -> "media" }, n)
            },

            Rule("media", { t -> t.any { it in setOf("pause", "resume", "skip", "next", "previous", "play", "stop") } && t.any { it in MEDIA_NOUNS || it in setOf("pause", "resume", "skip") } },
                setOf("pause", "play", "resume", "stop", "next", "previous", "skip", "music", "song", "track", "media", "the", "playback", "playing", "it", "this", "current", "video", "audio", "that")) { t ->
                when {
                    "pause" in t || "stop" in t -> LocalIntent.Media("pause")
                    "resume" in t || "play" in t -> LocalIntent.Media("play")
                    "previous" in t -> LocalIntent.Media("previous")
                    "next" in t || "skip" in t -> LocalIntent.Media("next")
                    else -> null
                }
            },

            Rule("recents", { t -> "recents" in t || ("recent" in t && t.any { it == "apps" || it == "app" || it == "applications" }) },
                setOf("open", "show", "the", "recent", "recents", "apps", "app", "applications", "switch", "between", "overview", "screen", "my", "go", "to", "multitasking")) { LocalIntent.Recents },

            Rule("lock", { t -> "lock" in t && t.none { it == "unlock" } },
                setOf("lock", "the", "phone", "screen", "my", "device", "now", "it", "down")) { LocalIntent.LockScreen },

            Rule("home", { t -> "home" in t && "back" !in t },
                setOf("go", "to", "the", "home", "screen", "press", "button", "take", "me", "return", "open", "my", "show")) { LocalIntent.Home },

            Rule("back", { t -> "back" in t && "home" !in t },
                setOf("go", "back", "press", "button", "previous", "screen", "the", "one", "step", "to")) { LocalIntent.Back },
        )
    }

    // ---------------- Slot helpers ----------------

    private fun hasPair(t: List<String>, a: String, b: String) = t.zipWithNext().any { it.first == a && it.second == b }

    private fun settingsPage(t: List<String>): String? {
        val words = t.filter { it !in SETTINGS_VOCAB }
        if (words.isEmpty()) return "main"
        // Longest phrase first: "battery saver" must not become "battery".
        var i = 0
        val pages = ArrayList<String>()
        while (i < words.size) {
            val two = if (i + 1 < words.size) words[i] + " " + words[i + 1] else null
            val three = if (i + 2 < words.size) two + " " + words[i + 2] else null
            val hit = three?.let { SETTINGS_ALIASES[it] }?.also { i += 3 }
                ?: two?.let { SETTINGS_ALIASES[it] }?.also { i += 2 }
                ?: SETTINGS_ALIASES[words[i]]?.also { i += 1 }
                ?: return null
            pages += hit
        }
        return pages.distinct().singleOrNull()
    }

    private fun timerSeconds(t: List<String>): Int? {
        var total = 0L
        var i = 0
        var pairs = 0
        while (i < t.size) {
            val w = t[i]
            val amount: Long? = when {
                w.all(Char::isDigit) -> w.toLongOrNull()
                w == "a" || w == "an" -> if (i + 1 < t.size && unitSeconds(t[i + 1]) != null) 1L else null
                else -> null
            }
            if (amount != null && i + 1 < t.size) {
                val unit = unitSeconds(t[i + 1])
                if (unit != null) { total += amount * unit; pairs++; i += 2; continue }
            }
            i++
        }
        // A bare number without a unit is ambiguous ("timer 5"): do not guess.
        val digitsUsed = t.count { it.all(Char::isDigit) }
        if (pairs == 0 || digitsUsed > pairs) return null
        return total.takeIf { it in 1..86_399 }?.toInt()
    }

    private fun unitSeconds(u: String): Long? = when (u) {
        "second", "seconds", "sec", "secs" -> 1L
        "minute", "minutes", "min", "mins" -> 60L
        "hour", "hours", "hr", "hrs" -> 3600L
        else -> null
    }

    private fun openApp(t: List<String>): RouteDecision? {
        if (t[0] !in OPEN_VERBS) return null
        var words = t.drop(1).dropWhile { it == "the" || it == "my" }
        while (words.isNotEmpty() && words.last() in setOf("app", "application")) words = words.dropLast(1)
        if (words.isEmpty() || words.size > 4) return RouteDecision.Escalate("open: no app name")
        val query = words.joinToString(" ")
        return when (val m = apps.resolve(query)) {
            is AppMatch.Hit -> RouteDecision.Local(listOf(LocalIntent.OpenApp(m.app)))
            is AppMatch.Ambiguous -> RouteDecision.Escalate("open: ambiguous app \"$query\" (${m.candidates.joinToString { it.label }})")
            AppMatch.None -> RouteDecision.Escalate("open: no installed app \"$query\"")
        }
    }

    private fun findApp(t: List<String>): RouteDecision? {
        val asksInstalled = "installed" in t
        val startsWithFind = t[0] in FIND_VERBS
        if (!asksInstalled && !startsWithFind && !(t.size >= 3 && t[0] == "do" && t[1] == "i" && t[2] == "have")) return null
        val drop = setOf("find", "locate", "search", "look", "for", "is", "are", "do", "i", "have", "you", "the", "my", "on", "phone", "installed", "check", "if", "whether", "there", "any", "a", "an", "where", "me", "show")
        var words = t.filter { it !in drop }
        val explicitApp = words.any { it == "app" || it == "application" } || asksInstalled
        words = words.filter { it != "app" && it != "application" }
        if (words.isEmpty() || words.size > 4) return RouteDecision.Escalate("find: no app name")
        val query = words.joinToString(" ")
        return when (val m = apps.resolve(query)) {
            is AppMatch.Hit, is AppMatch.Ambiguous -> RouteDecision.Local(listOf(LocalIntent.FindApp(query, m)))
            AppMatch.None -> if (explicitApp) RouteDecision.Local(listOf(LocalIntent.FindApp(query, m))) else RouteDecision.Escalate("find: not an installed app")
        }
    }

    // ---------------- Tokenising ----------------

    private fun tokenize(raw: String): List<String> {
        var s = raw.lowercase().replace('\u2019', '\'').replace("'", "")
        s = s.replace("wi-fi", "wifi").replace("wi fi", "wifi")
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c.isLetterOrDigit()) c else ' ')
        var tokens = sb.toString().split(' ').filter { it.isNotEmpty() }
        var changed = true
        while (changed && tokens.isNotEmpty()) {
            changed = false
            for (p in LEADING_FILLER) if (tokens.size > p.size && tokens.take(p.size) == p) { tokens = tokens.drop(p.size); changed = true; break }
            if (changed) continue
            for (p in TRAILING_FILLER) if (tokens.size > p.size && tokens.takeLast(p.size) == p) { tokens = tokens.dropLast(p.size); changed = true; break }
        }
        return tokens
    }

    companion object {
        const val MAX_CHARS = 120
        const val MAX_TOKENS = 12

        private val OPEN_VERBS = setOf("open", "launch", "start", "run")
        private val FIND_VERBS = setOf("find", "locate", "search", "look")
        private val JOINERS = setOf("and", "then")
        private val MEDIA_NOUNS = setOf("music", "song", "track", "media", "video", "audio", "playback")

        private fun phrase(s: String) = s.split(' ')

        private val LEADING_FILLER = listOf(
            "hey nyro", "ok nyro", "okay nyro", "hi nyro", "nyro", "please", "can you", "could you", "would you", "will you", "i want you to", "i need you to", "i would like you to",
            "i want to", "kindly", "just", "go ahead and", "quickly",
        ).map(::phrase)
        private val TRAILING_FILLER = listOf("please", "for me", "now", "thanks", "thank you", "quickly", "right now").map(::phrase)

        private val SETTINGS_VOCAB = setOf("open", "show", "go", "to", "the", "my", "settings", "setting", "page", "screen", "menu", "take", "me", "up", "bring", "phone", "device")

        /** Phrase → page names accepted by the open_settings tool. */
        private val SETTINGS_ALIASES = mapOf(
            "wifi" to "wifi", "bluetooth" to "bluetooth", "mobile data" to "mobile_data", "cellular" to "mobile_data", "data" to "mobile_data",
            "airplane" to "airplane", "airplane mode" to "airplane", "flight mode" to "airplane", "flight" to "airplane",
            "display" to "display", "brightness" to "display", "sound" to "sound", "sounds" to "sound", "volume" to "sound",
            "battery saver" to "battery_saver", "power saving" to "battery_saver", "battery" to "battery", "apps" to "apps", "applications" to "apps", "app" to "apps",
            "notification" to "notifications", "notifications" to "notifications", "location" to "location", "gps" to "location",
            "security" to "security", "privacy" to "privacy", "accessibility" to "accessibility", "date time" to "date_time", "date and time" to "date_time", "language" to "language",
            "storage" to "storage", "developer" to "developer", "developer options" to "developer", "about" to "about", "about phone" to "about", "nfc" to "nfc",
            "hotspot" to "hotspot", "tethering" to "hotspot", "do not disturb" to "dnd", "dnd" to "dnd", "wallpaper" to "wallpaper",
            "internet" to "internet_panel", "main" to "main",
        )
        private val SETTINGS_ALIAS_WORDS = SETTINGS_ALIASES.keys.flatMap { it.split(' ') }.toSet()
    }
}
