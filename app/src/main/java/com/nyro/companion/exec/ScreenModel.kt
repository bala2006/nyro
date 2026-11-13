package com.nyro.companion.exec

/**
 * Plain-data view of one screen. No Android types, so the execution engine, the loop detector and the
 * renderer can be tested on the JVM. [com.nyro.companion.access.ScreenSnapshot] converts to this.
 */
data class ObsElement(
    val id: Int,
    val role: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val left: Int = 0,
    val top: Int = 0,
    val right: Int = 0,
    val bottom: Int = 0,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val scrollable: Boolean = false,
    val checked: Boolean? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
    val password: Boolean = false,
) {
    val label: String get() = text.ifBlank { desc }.ifBlank { viewId }.ifBlank { role }
    val interactive: Boolean get() = clickable || editable || scrollable || checked != null
    val centerX: Int get() = (left + right) / 2
    val centerY: Int get() = (top + bottom) / 2
}

data class Obs(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val elements: List<ObsElement>,
    val keyboardOpen: Boolean = false,
    val screenW: Int = 0,
    val screenH: Int = 0,
    val truncated: Boolean = false,
    /** Monotonic time the snapshot finished. */
    val capturedAt: Long = 0,
    /** Accessibility event counter when the snapshot started; a higher live counter means it may be stale. */
    val eventVersion: Long = 0,
) {
    /** Identity of the screen for navigation: stable under scrolling, counters and animation. */
    val layoutSignature: Long by lazy(LazyThreadSafetyMode.NONE) { ScreenSignature.layout(this) }

    /** Changes whenever any visible text or state changes. */
    val contentSignature: Long by lazy(LazyThreadSafetyMode.NONE) { ScreenSignature.content(this) }

    fun change(from: Obs?): ScreenChange = ScreenSignature.compare(from, this)

    fun byId(id: Int?): ObsElement? = id?.let { i -> elements.firstOrNull { it.id == i } }
}

enum class ScreenChange {
    /** Nothing visible differs. */
    NONE,
    /** Same screen, some text or state differs (progress, counters, list content). */
    CONTENT,
    /** A different screen (new package, or a different set of controls and labels). */
    LAYOUT,
}

object ScreenSignature {
    private const val FNV_OFFSET = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
    private const val FNV_PRIME = 0x100000001b3L

    private fun hash(seed: Long, s: String): Long {
        var h = seed
        for (ch in s) { h = (h xor ch.code.toLong()) * FNV_PRIME }
        return (h xor 0x1f) * FNV_PRIME
    }

    private val digits = Regex("[0-9]+")

    /** Lower-case, digits masked: "Score 120" and "Score 135" share a label, so ticking counters do not look like navigation. */
    fun normalizeLabel(s: String): String = digits.replace(s.lowercase().trim(), "#")

    private fun volatileLabel(n: String): Boolean = n.isEmpty() || n.all { it == '#' || it == ':' || it == '%' || it == '.' || it == ',' || it == ' ' || it == '/' }

    fun layout(o: Obs): Long {
        var h = hash(FNV_OFFSET, o.packageName)
        val labels = HashSet<String>()
        for (e in o.elements.take(120)) {
            val n = normalizeLabel(e.text.ifBlank { e.desc })
            if (volatileLabel(n) && e.viewId.isBlank()) continue
            labels += (if (e.interactive) "i:" else "t:") + e.role + ":" + n.take(60) + ":" + e.viewId
        }
        for (l in labels.sorted()) h = hash(h, l)
        return h
    }

    fun content(o: Obs): Long {
        var h = hash(FNV_OFFSET, o.packageName)
        for (e in o.elements) {
            h = hash(h, e.role); h = hash(h, e.text); h = hash(h, e.desc)
            h = hash(h, "${e.checked}${e.selected}${e.enabled}")
        }
        return h
    }

    fun compare(before: Obs?, after: Obs): ScreenChange = when {
        before == null -> ScreenChange.LAYOUT
        before.layoutSignature != after.layoutSignature -> ScreenChange.LAYOUT
        before.contentSignature != after.contentSignature -> ScreenChange.CONTENT
        else -> ScreenChange.NONE
    }
}

/** Renders observations for the model. [full] keeps the legacy read_screen format; [compact] is for action results. */
object ObsRenderer {
    fun full(o: Obs, maxElements: Int = 120): String = buildString {
        header(o)
        if (o.elements.isEmpty()) append("(No accessible elements. The app may draw its UI without accessibility info; consider take_screenshot.)\n")
        o.elements.take(maxElements).forEach { e ->
            append('[').append(e.id).append("] ").append(e.role)
            if (e.text.isNotBlank()) append(" \"").append(e.text).append('"')
            if (e.desc.isNotBlank() && e.desc != e.text) append(" desc=\"").append(e.desc).append('"')
            if (e.viewId.isNotBlank()) append(" id=").append(e.viewId)
            flags(e, longForm = true)
            append(" @").append(e.centerX).append(',').append(e.centerY)
            append('\n')
        }
        if (o.truncated || o.elements.size > maxElements) append("(More elements exist; scroll or use find_elements.)\n")
    }

    /**
     * Short form for step results: no coordinates (the model acts by id), no resource ids when a label exists,
     * duplicate rows collapsed. Static text is kept because it carries the screen's meaning.
     */
    fun compact(o: Obs, maxElements: Int = 60): String = buildString {
        header(o)
        if (o.elements.isEmpty()) { append("(No accessible elements; consider take_screenshot.)\n"); return@buildString }
        val seen = HashSet<String>()
        var shown = 0
        var skipped = 0
        for (e in o.elements) {
            val key = e.role + "|" + e.text + "|" + e.desc + "|" + e.interactive
            if (!seen.add(key) && !e.interactive) { skipped++; continue }
            if (shown >= maxElements) { skipped++; continue }
            shown++
            append('[').append(e.id).append("] ").append(e.role)
            if (e.text.isNotBlank()) append(" \"").append(e.text).append('"')
            if (e.desc.isNotBlank() && e.desc != e.text) append(" desc=\"").append(e.desc).append('"')
            if (e.text.isBlank() && e.desc.isBlank() && e.viewId.isNotBlank()) append(" id=").append(e.viewId)
            flags(e, longForm = false)
            append('\n')
        }
        if (skipped > 0 || o.truncated) append("(+").append(skipped).append(" more or repeated; scroll or find_elements)\n")
    }

    /** One line telling the model nothing changed, so an unchanged screen is not sent again. */
    fun unchanged(o: Obs): String = "Screen unchanged: still ${o.appLabel.ifBlank { o.packageName }} with ${o.elements.size} elements; the ids from your last screen result are still valid."

    private fun StringBuilder.header(o: Obs) {
        append("App: ").append(o.appLabel.ifBlank { o.packageName }).append(" (").append(o.packageName).append(")")
        if (o.title.isNotBlank()) append(" · Screen: ").append(o.title)
        if (o.screenW > 0) append(" · Display ").append(o.screenW).append("x").append(o.screenH)
        if (o.keyboardOpen) append(" · Keyboard open")
        append('\n')
    }

    private fun StringBuilder.flags(e: ObsElement, longForm: Boolean) {
        val f = buildList {
            if (e.clickable) add("tap")
            if (e.editable) add("input")
            if (e.scrollable) add("scroll")
            if (e.checked != null) add(if (e.checked) "checked" else "unchecked")
            if (e.selected) add("selected")
            if (e.password) add("password")
            if (!e.enabled) add("disabled")
        }
        if (f.isNotEmpty()) append(" {").append(f.joinToString(if (longForm) "," else ",")).append('}')
    }
}
