package com.nyro.companion.access

import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo

/** One actionable/visible element of the current screen, addressed by a short numeric id. */
data class UiElement(
    val id: Int,
    val node: AccessibilityNodeInfo,
    val role: String,
    val text: String,
    val desc: String,
    val viewId: String,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checked: Boolean?,
    val password: Boolean,
    val selected: Boolean,
    val enabled: Boolean,
)

data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val elements: List<UiElement>,
    val keyboardOpen: Boolean,
    val screenW: Int,
    val screenH: Int,
    val truncated: Boolean,
) {
    /** Cheap fingerprint used to detect whether an action changed the screen. */
    val signature: Int get() = (packageName + "|" + elements.joinToString("|") { it.role + it.text + it.desc + it.bounds.top / 40 }).hashCode()

    fun render(maxElements: Int = 120): String = buildString {
        append("App: ").append(appLabel.ifBlank { packageName }).append(" (").append(packageName).append(")")
        if (title.isNotBlank()) append(" · Screen: ").append(title)
        append(" · Display ").append(screenW).append("x").append(screenH)
        if (keyboardOpen) append(" · Keyboard open")
        append('\n')
        if (elements.isEmpty()) append("(No accessible elements. The app may draw its UI without accessibility info; consider take_screenshot.)\n")
        elements.take(maxElements).forEach { e ->
            append('[').append(e.id).append("] ").append(e.role)
            if (e.text.isNotBlank()) append(" \"").append(e.text).append('"')
            if (e.desc.isNotBlank() && e.desc != e.text) append(" desc=\"").append(e.desc).append('"')
            if (e.viewId.isNotBlank()) append(" id=").append(e.viewId)
            val flags = buildList {
                if (e.clickable) add("tap")
                if (e.editable) add("input")
                if (e.scrollable) add("scroll")
                if (e.checked != null) add(if (e.checked) "checked" else "unchecked")
                if (e.selected) add("selected")
                if (e.password) add("password")
                if (!e.enabled) add("disabled")
            }
            if (flags.isNotEmpty()) append(" {").append(flags.joinToString(",")).append('}')
            append(" @").append(e.bounds.centerX()).append(',').append(e.bounds.centerY())
            append('\n')
        }
        if (truncated || elements.size > maxElements) append("(More elements exist; scroll or use find_elements.)\n")
    }
}

object ScreenReader {
    private const val MAX_NODES = 1500
    private const val MAX_ELEMENTS = 160

    fun pickRoot(svc: NyroAccessibilityService): AccessibilityNodeInfo? {
        val own = svc.packageName
        val windows = runCatching { svc.windows }.getOrNull().orEmpty()
        // 1) The active window (an app, the notification shade, a system dialog), unless it is
        //    one of NYRO's own floating overlays. NYRO's own activity is a normal app window.
        windows.firstOrNull { it.isActive || it.isFocused }?.let { w ->
            val r = w.root
            if (r != null && (w.type == AccessibilityWindowInfo.TYPE_APPLICATION || r.packageName?.toString() != own)) return r
        }
        // 2) Otherwise (NYRO's panel has focus) the topmost application window beneath it.
        //    NYRO's overlays are never TYPE_APPLICATION, so they are excluded here.
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }?.root?.let { return it }
        return svc.rootInActiveWindow
    }

    fun keyboardOpen(svc: NyroAccessibilityService): Boolean =
        runCatching { svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    fun snapshot(svc: NyroAccessibilityService, appLabel: (String) -> String): ScreenSnapshot {
        val dm = svc.resources.displayMetrics
        val root = pickRoot(svc)
            ?: return ScreenSnapshot("", "", "", emptyList(), keyboardOpen(svc), dm.widthPixels, dm.heightPixels, false)
        val pkg = root.packageName?.toString().orEmpty()
        val out = ArrayList<UiElement>()
        var visited = 0
        var truncated = false
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        var title = ""

        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (visited++ > MAX_NODES) { truncated = true; return }
            if (!n.isVisibleToUser) return
            val b = Rect(); n.getBoundsInScreen(b)
            if (!Rect.intersects(b, screen) || b.width() <= 1 || b.height() <= 1) {
                // still descend: some containers report zero size but have visible children
                if (b.isEmpty && n.childCount == 0) return
            }
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            val hint = n.hintText?.toString()?.trim().orEmpty()
            val cls = n.className?.toString().orEmpty()
            val role = roleOf(cls, n)
            if (title.isEmpty() && n.isHeading && text.isNotBlank()) title = text
            val interesting = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable ||
                text.isNotEmpty() || desc.isNotEmpty()
            if (interesting && out.size < MAX_ELEMENTS && b.width() > 1 && b.height() > 1) {
                out += UiElement(
                    id = out.size + 1,
                    node = n,
                    role = role,
                    text = clip(if (n.isPassword) "•••" else text.ifEmpty { if (n.isEditable) hint.let { if (it.isNotEmpty()) "hint: $it" else "" } else "" }),
                    desc = clip(desc),
                    viewId = n.viewIdResourceName?.substringAfter(":id/").orEmpty(),
                    bounds = b,
                    clickable = n.isClickable || n.isLongClickable,
                    editable = n.isEditable,
                    scrollable = n.isScrollable,
                    checked = if (n.isCheckable) n.isChecked else null,
                    password = n.isPassword,
                    selected = n.isSelected,
                    enabled = n.isEnabled,
                )
            } else if (interesting && out.size >= MAX_ELEMENTS) truncated = true
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        // Collapse text-only children directly inside a clickable parent: keep the list short and readable.
        val compact = dedupe(out)
        return ScreenSnapshot(pkg, appLabel(pkg), title, compact, keyboardOpen(svc), dm.widthPixels, dm.heightPixels, truncated)
    }

    private fun dedupe(list: List<UiElement>): List<UiElement> {
        val result = ArrayList<UiElement>()
        for (e in list) {
            val parent = result.lastOrNull { p -> p.clickable && p.bounds.contains(e.bounds) && p.text.isEmpty() && p.desc.isEmpty() }
            if (parent != null && !e.clickable && !e.editable && !e.scrollable && e.checked == null && e.text.isNotEmpty()) {
                // Label the unlabeled clickable container with its child text instead of a separate row.
                val idx = result.indexOf(parent)
                result[idx] = parent.copy(text = e.text)
                continue
            }
            result += e
        }
        return result.mapIndexed { i, e -> e.copy(id = i + 1) }
    }

    private fun clip(s: String) = s.replace('\n', ' ').let { if (it.length > 90) it.take(87) + "…" else it }

    private fun roleOf(cls: String, n: AccessibilityNodeInfo): String {
        val c = cls.substringAfterLast('.')
        return when {
            n.isEditable -> "Input"
            c.contains("Switch") -> "Switch"
            c.contains("CheckBox") -> "Checkbox"
            c.contains("RadioButton") -> "Radio"
            c.contains("Button") -> "Button"
            c.contains("Image") && n.isClickable -> "ImageButton"
            c.contains("Image") -> "Image"
            c.contains("Tab") -> "Tab"
            n.isScrollable -> "List"
            c.contains("TextView") || c == "View" && n.text != null -> if (n.isClickable) "Item" else "Text"
            n.isClickable -> "Item"
            else -> "View"
        }
    }

    // ---------------- Node actions ----------------

    /** Ensures the node is still on screen; returns a fresh equivalent from [fresh] if needed. */
    fun resolve(e: UiElement, fresh: ScreenSnapshot?): UiElement? {
        if (e.node.refresh() && e.node.isVisibleToUser) return e
        fresh ?: return null
        return fresh.elements.firstOrNull { it.viewId == e.viewId && it.text == e.text && it.desc == e.desc && it.viewId.isNotEmpty() }
            ?: fresh.elements.firstOrNull { it.text == e.text && it.desc == e.desc && it.role == e.role && (it.text.isNotEmpty() || it.desc.isNotEmpty()) }
    }

    fun clickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isClickable && cur.isEnabled && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; hops++
        }
        return false
    }

    fun longClickNode(n: AccessibilityNodeInfo): Boolean {
        var cur: AccessibilityNodeInfo? = n
        var hops = 0
        while (cur != null && hops < 6) {
            if (cur.isLongClickable && cur.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
            cur = cur.parent; hops++
        }
        return false
    }

    fun setText(n: AccessibilityNodeInfo, text: String): Boolean {
        if (!n.isFocused) n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text) }
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun imeEnter(n: AccessibilityNodeInfo): Boolean = n.performAction(AccessibilityAction.ACTION_IME_ENTER.id)

    fun scrollNode(n: AccessibilityNodeInfo, direction: String): Boolean {
        val directional = when (direction) {
            "down" -> AccessibilityAction.ACTION_SCROLL_DOWN
            "up" -> AccessibilityAction.ACTION_SCROLL_UP
            "left" -> AccessibilityAction.ACTION_SCROLL_LEFT
            "right" -> AccessibilityAction.ACTION_SCROLL_RIGHT
            else -> null
        }
        if (directional != null && n.actionList.any { it.id == directional.id } && n.performAction(directional.id)) return true
        val fallback = if (direction == "down" || direction == "right") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return n.performAction(fallback)
    }

    fun focusedInput(svc: NyroAccessibilityService): AccessibilityNodeInfo? =
        svc.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)?.takeIf { it.isEditable }
}
