package com.nyro.companion.access

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import com.nyro.companion.exec.Obs
import com.nyro.companion.exec.ObsElement
import com.nyro.companion.exec.ObsRenderer
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf
import com.nyro.companion.perf.RealClock

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
) {
    fun toObs() = ObsElement(
        id = id, role = role, text = text, desc = desc, viewId = viewId,
        left = bounds.left, top = bounds.top, right = bounds.right, bottom = bounds.bottom,
        clickable = clickable, editable = editable, scrollable = scrollable, checked = checked,
        selected = selected, enabled = enabled, password = password,
    )
}

data class ScreenSnapshot(
    val packageName: String,
    val appLabel: String,
    val title: String,
    val elements: List<UiElement>,
    val keyboardOpen: Boolean,
    val screenW: Int,
    val screenH: Int,
    val truncated: Boolean,
    /** Monotonic time the walk finished. */
    val capturedAt: Long = 0L,
    /** Accessibility event counter when the walk started; if the live counter is higher, this snapshot may be stale. */
    val eventVersion: Long = 0L,
) {
    /** Android-free view used by the execution engine, signatures and renderers. */
    val obs: Obs by lazy(LazyThreadSafetyMode.NONE) {
        Obs(packageName, appLabel, title, elements.map { it.toObs() }, keyboardOpen, screenW, screenH, truncated, capturedAt, eventVersion)
    }

    fun render(maxElements: Int = 120): String = ObsRenderer.full(obs, maxElements)
    fun renderCompact(maxElements: Int = 60): String = ObsRenderer.compact(obs, maxElements)
}

object ScreenReader {
    private const val MAX_NODES = 1500
    private const val MAX_ELEMENTS = 260

    /** Default wall-clock budget for one tree walk; a slow app returns a truncated snapshot instead of blocking the task. */
    const val FULL_BUDGET_MS = 2500L
    const val OBSERVE_BUDGET_MS = 1200L

    /** API 33+: fetch the window's descendants in batches instead of one IPC per node. */
    private const val PREFETCH = AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID or AccessibilityNodeInfo.FLAG_PREFETCH_UNINTERRUPTIBLE

    fun pickRoot(svc: NyroAccessibilityService): AccessibilityNodeInfo? {
        val own = svc.packageName
        val windows = runCatching { svc.windows }.getOrNull().orEmpty()
        // 1) The active window (an app, the notification shade, a system dialog), unless it is
        //    one of NYRO's own floating overlays. NYRO's own activity is a normal app window.
        windows.firstOrNull { it.isActive || it.isFocused }?.let { w ->
            val r = w.getRoot(PREFETCH)
            if (r != null && (w.type == AccessibilityWindowInfo.TYPE_APPLICATION || r.packageName?.toString() != own)) return r
        }
        // 2) Otherwise (NYRO's panel has focus) the topmost application window beneath it.
        //    NYRO's overlays are never TYPE_APPLICATION, so they are excluded here.
        windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            .maxByOrNull { it.layer }?.getRoot(PREFETCH)?.let { return it }
        return svc.getRootInActiveWindow(PREFETCH)
    }

    /** Package of the app window the user is looking at (NYRO's own overlays excluded), or empty. */
    fun activePackage(svc: NyroAccessibilityService): String = pickRoot(svc)?.packageName?.toString().orEmpty()

    fun keyboardOpen(svc: NyroAccessibilityService): Boolean =
        runCatching { svc.windows.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } }.getOrDefault(false)

    private fun labelOf(n: AccessibilityNodeInfo): Pair<String, String> {
        val text = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val hint = n.hintText?.toString()?.trim().orEmpty()
        val shown = clip(if (n.isPassword) "•••" else text.ifEmpty { if (n.isEditable) hint.let { if (it.isNotEmpty()) "hint: $it" else "" } else "" })
        return shown to clip(desc)
    }

    private fun elementOf(n: AccessibilityNodeInfo, id: Int, b: Rect): UiElement {
        val (text, desc) = labelOf(n)
        return UiElement(
            id = id,
            node = n,
            role = roleOf(n.className?.toString().orEmpty(), n),
            text = text,
            desc = desc,
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
    }

    /**
     * Walks the visible tree into numbered elements. Bounded by node count and by [budgetMs]: a heavy WebView or
     * list returns a truncated snapshot (flagged) rather than blocking the agent for seconds.
     */
    fun snapshot(
        svc: NyroAccessibilityService,
        appLabel: (String) -> String,
        budgetMs: Long = FULL_BUDGET_MS,
        maxElements: Int = MAX_ELEMENTS,
    ): ScreenSnapshot = Perf.time(Metric.SNAPSHOT) {
        val startVersion = svc.gate.version
        val dm = svc.resources.displayMetrics
        val root = pickRoot(svc)
            ?: return@time ScreenSnapshot("", "", "", emptyList(), keyboardOpen(svc), dm.widthPixels, dm.heightPixels, false, RealClock.now(), startVersion)
        val pkg = root.packageName?.toString().orEmpty()
        val out = ArrayList<UiElement>()
        var visited = 0
        var truncated = false
        val screen = Rect(0, 0, dm.widthPixels, dm.heightPixels)
        var title = ""
        val deadline = SystemClock.uptimeMillis() + budgetMs

        fun walk(n: AccessibilityNodeInfo) {
            if (visited++ > MAX_NODES || SystemClock.uptimeMillis() > deadline) { truncated = true; return }
            if (!n.isVisibleToUser) return
            val b = Rect(); n.getBoundsInScreen(b)
            if (!Rect.intersects(b, screen) || b.width() <= 1 || b.height() <= 1) {
                // still descend: some containers report zero size but have visible children
                if (b.isEmpty && n.childCount == 0) return
            }
            val text = n.text?.toString()?.trim().orEmpty()
            val desc = n.contentDescription?.toString()?.trim().orEmpty()
            if (title.isEmpty() && n.isHeading && text.isNotBlank()) title = text
            val interesting = n.isClickable || n.isLongClickable || n.isEditable || n.isScrollable || n.isCheckable ||
                text.isNotEmpty() || desc.isNotEmpty()
            if (interesting && out.size < maxElements && b.width() > 1 && b.height() > 1) {
                out += elementOf(n, out.size + 1, b)
            } else if (interesting && out.size >= maxElements) truncated = true
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        // Collapse text-only children directly inside a clickable parent: keep the list short and readable.
        val compact = dedupe(out)
        ScreenSnapshot(pkg, appLabel(pkg), title, compact, keyboardOpen(svc), dm.widthPixels, dm.heightPixels, truncated, RealClock.now(), startVersion)
    }

    /**
     * Targeted lookup: asks the app process for nodes containing [query] (text or description, case-insensitive)
     * instead of walking the whole tree. Returns only visible nodes. Ids are 0: these elements are for immediate use.
     */
    fun findByText(svc: NyroAccessibilityService, query: String): List<UiElement> = Perf.time(Metric.LOOKUP) {
        val root = pickRoot(svc) ?: return@time emptyList()
        val nodes = runCatching { root.findAccessibilityNodeInfosByText(query) }.getOrNull().orEmpty()
        nodes.mapNotNull { n ->
            if (!n.isVisibleToUser) return@mapNotNull null
            val b = Rect(); n.getBoundsInScreen(b)
            if (b.width() <= 1 || b.height() <= 1) null else elementOf(n, 0, b)
        }
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

    /**
     * True when the live node still shows what the model was shown. RecyclerView recycles nodes: the same node can
     * now hold a different row, and tapping it would hit the wrong thing.
     */
    private fun stillShows(e: UiElement): Boolean {
        // The text of an input field is meant to change (the user or NYRO just typed into it); it is still the same field.
        if (e.editable) return true
        val (text, desc) = labelOf(e.node)
        return text == e.text && desc == e.desc
    }

    /**
     * Returns the element to act on, or null if it is gone. The node is refreshed (one IPC) and compared with what the
     * model saw; only if that fails is [fresh] called to re-read the screen. The fresh read is lazy: the old code read the
     * whole screen before every tap even when the node was fine.
     */
    fun resolve(e: UiElement, fresh: () -> ScreenSnapshot?): UiElement? = Perf.time(Metric.LOOKUP) {
        if (e.node.refresh() && e.node.isVisibleToUser && stillShows(e)) {
            // Bounds captured at read time can be stale after a scroll or animation: use the node's current bounds.
            val b = Rect(); e.node.getBoundsInScreen(b)
            return@time e.copy(bounds = b)
        }
        val snap = fresh() ?: return@time null
        val sameId = if (e.viewId.isNotEmpty()) snap.elements.filter { it.viewId == e.viewId } else emptyList()
        sameId.singleOrNull()
            ?: snap.elements.firstOrNull { it.viewId == e.viewId && it.text == e.text && it.desc == e.desc && it.viewId.isNotEmpty() }
            ?: snap.elements.firstOrNull { it.text == e.text && it.desc == e.desc && it.role == e.role && (it.text.isNotEmpty() || it.desc.isNotEmpty()) }
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
