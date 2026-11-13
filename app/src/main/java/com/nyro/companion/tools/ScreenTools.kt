package com.nyro.companion.tools

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.util.Base64
import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.ScreenCaptureManager
import com.nyro.companion.access.ScreenReader
import com.nyro.companion.access.ScreenSnapshot
import com.nyro.companion.access.UiElement
import com.nyro.companion.ai.Msg
import com.nyro.companion.exec.Freshness
import com.nyro.companion.exec.ImageHash
import com.nyro.companion.exec.Obs
import com.nyro.companion.exec.ObsRenderer
import com.nyro.companion.exec.ScreenChange
import com.nyro.companion.overlay.UiFeedback
import com.nyro.companion.perf.Metric
import com.nyro.companion.perf.Perf
import com.nyro.companion.perf.RealClock
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Shared state between screen tools within a task: the snapshot that element ids refer to. */
object ScreenSession {
    @Volatile var last: ScreenSnapshot? = null
    /** Screenshot pixels per screen pixel for the latest screenshot. */
    @Volatile var shotScale: Float = 1f

    /** The screen as last written into a tool result. An identical screen is reported as "unchanged" instead of being sent again. */
    @Volatile private var lastSent: Obs? = null
    @Volatile private var quiet = false

    /** Runs [block] with renders not recorded as sent (plans and workflows return one report at the end). */
    suspend fun <T> withoutRendering(block: suspend () -> T): T {
        quiet = true
        try { return block() } finally { quiet = false }
    }

    /** Hash of the last picture sent to Azure. */
    @Volatile private var lastShotHash: Long? = null

    fun svc(): NyroAccessibilityService? = NyroAccessibilityService.current

    /** Call at the start of a task and after context compaction: the model no longer remembers earlier renders. */
    fun resetSent() { lastSent = null; lastShotHash = null }

    fun read(svc: NyroAccessibilityService, budgetMs: Long = ScreenReader.FULL_BUDGET_MS): ScreenSnapshot =
        ScreenReader.snapshot(svc, appLabel = { NyroApp.graph.apps.label(it) }, budgetMs = budgetMs).also { last = it }

    /**
     * The last snapshot if nothing relevant happened since it was taken and it is younger than [maxAgeMs]; otherwise a new one.
     * "Nothing relevant" means the accessibility event counter has not moved (NYRO's own overlay events do not count).
     */
    fun fresh(svc: NyroAccessibilityService, maxAgeMs: Long = 250, budgetMs: Long = ScreenReader.OBSERVE_BUDGET_MS): ScreenSnapshot {
        val cur = last
        if (cur != null && Freshness.isFresh(cur.eventVersion, cur.capturedAt, svc.gate.version, RealClock.now(), maxAgeMs)) return cur
        return read(svc, budgetMs)
    }

    /**
     * Waits for the UI to react to an action started when the event counter was [since], reads the result once and
     * says how it differs from [before] (the screen the model was acting on). Unchanged screens are not re-sent.
     */
    suspend fun observe(svc: NyroAccessibilityService, before: Obs?, since: Long, actionNote: String, maxElements: Int = 60): ToolResult {
        val settle = Perf.time(Metric.UI_WAIT) { svc.gate.awaitSettled(since) }
        val snap = read(svc, ScreenReader.OBSERVE_BUDGET_MS)
        val obs = snap.obs
        val change = Perf.time(Metric.VERIFY) { obs.change(before) }
        return ToolResult(buildString {
            append(actionNote).append('\n')
            append("Screen: ").append(when (change) {
                ScreenChange.LAYOUT -> "changed (new screen)"
                ScreenChange.CONTENT -> "updated (same screen, content differs)"
                ScreenChange.NONE -> "unchanged (the action may not have had a visible effect)"
            })
            if (!settle.quiet) append(" · still animating")
            append('\n')
            append(renderOnce(obs, maxElements))
        }, change = change, obs = obs)
    }

    /** Compact render, or a one-liner when the model already has this exact screen. */
    fun renderOnce(obs: Obs, maxElements: Int = 60, full: Boolean = false): String {
        // While a plan or workflow runs, tool results are not shown to the model, so they must not count as "already sent".
        if (quiet) return if (full) ObsRenderer.full(obs, maxElements) else ObsRenderer.compact(obs, maxElements)
        val prev = lastSent
        lastSent = obs
        return when {
            prev != null && prev.packageName == obs.packageName && prev.contentSignature == obs.contentSignature -> ObsRenderer.unchanged(obs)
            full -> ObsRenderer.full(obs, maxElements)
            else -> ObsRenderer.compact(obs, maxElements)
        }
    }

    fun element(id: Int?): UiElement? = id?.let { i -> last?.elements?.firstOrNull { it.id == i } }

    /** True if [hash] is the same picture as the last one sent; otherwise remembers it. */
    fun isDuplicateShot(hash: Long): Boolean {
        val prev = lastShotHash
        if (prev != null && ImageHash.distance(prev, hash) <= ImageHash.SAME_THRESHOLD) return true
        lastShotHash = hash
        return false
    }
}

private fun needSvc(): NyroAccessibilityService? = ScreenSession.svc()
private fun noSvc() = ToolResult.fail(Requirement.ACCESSIBILITY.label)

/**
 * Ranks text matches for tap_text. An exact label on an enabled, clickable button beats a message
 * bubble or a long sentence that merely contains the word (e.g. "send" inside a chat message).
 */
private fun score(e: UiElement, q: String): Int {
    val t = e.text.lowercase(); val d = e.desc.lowercase()
    var s = 0
    if (t == q || d == q) s += 100
    if (e.clickable) s += 30
    if (e.role == "Button" || e.role == "ImageButton") s += 15
    if (e.editable) s -= 10
    if (t.length > q.length * 3 + 10) s -= 25
    if (!e.enabled) s -= 40
    return s
}

private fun label(e: UiElement) = e.text.ifBlank { e.desc }.ifBlank { e.viewId }.ifBlank { e.role }

fun screenTools(): List<Tool> = listOf(
    object : Tool(
        "read_screen",
        "Read the current screen through accessibility: app, title and a numbered list of visible elements with roles, text, flags and center coordinates. Use before interacting. Action results already include the new screen, so do not call this right after an action. Element ids are valid until the screen changes.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true, readOnly = true,
    ) {
        override fun describe(args: Args) = "Reading the screen"
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            UiFeedback.scan()
            Perf.time(Metric.UI_WAIT) { svc.gate.awaitQuiet(quietMs = 120, maxMs = 800) }
            val obs = ScreenSession.read(svc).obs
            // read_screen is the explicit "give me everything" call, so a new screen is rendered in the full format.
            return ToolResult(ScreenSession.renderOnce(obs, 220, full = true), obs = obs)
        }
    },

    object : Tool(
        "find_elements",
        "Search the current screen for elements whose text, description or resource id contains the query (case-insensitive).",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true, readOnly = true,
        params = { string("query", "Text to look for") },
    ) {
        override fun describe(args: Args) = "Looking for \"${args.str("query")}\""
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val q = args.str("query")!!.lowercase()
            UiFeedback.scan()
            val snap = ScreenSession.fresh(svc, 200)
            val hits = snap.elements.filter { (it.text + " " + it.desc + " " + it.viewId).lowercase().contains(q) }
            if (hits.isEmpty()) return ToolResult("No element matching \"$q\" on ${snap.appLabel}. ${snap.elements.size} elements visible; consider scrolling or take_screenshot.")
            return ToolResult(snap.copy(elements = hits).render(40))
        }
    },

    object : Tool(
        "tap_element",
        "Tap an element by its id from the latest read_screen/find_elements/action result. Preferred over coordinates.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = {
            int("element_id", "Element id, e.g. 12")
            enum("method", "auto (default) uses an accessibility click; gesture dispatches a real touch", listOf("auto", "gesture"), required = false)
        },
    ) {
        override fun describe(args: Args) = ScreenSession.element(args.int("element_id"))?.let { "Tapping \"${label(it)}\"" } ?: "Tapping element ${args.int("element_id")}"
        override fun assess(args: Args): RiskAssessment {
            val e = ScreenSession.element(args.int("element_id")) ?: return RiskAssessment(Risk.LOW, describe(args))
            return SensitiveUi.classify(label(e), ScreenSession.last?.packageName.orEmpty(), e.checked != null) ?: RiskAssessment(Risk.LOW, describe(args))
        }
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val e0 = ScreenSession.element(args.int("element_id")) ?: return ToolResult.fail("Unknown element id. Call read_screen first.")
            val before = ScreenSession.last?.obs
            val e = ScreenReader.resolve(e0) { ScreenSession.read(svc, ScreenReader.OBSERVE_BUDGET_MS) }
                ?: return ToolResult.fail("Element \"${label(e0)}\" is no longer on screen. Read the screen again.")
            if (!e.enabled) return ToolResult.fail("Element \"${label(e)}\" is disabled.")
            UiFeedback.focus(e.bounds)
            val since = svc.gate.version
            val how = if (args.str("method") != "gesture" && ScreenReader.clickNode(e.node)) {
                UiFeedback.showTap(e.bounds.exactCenterX(), e.bounds.exactCenterY()); "accessibility click"
            } else if (svc.tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) "gesture tap at ${e.bounds.centerX()},${e.bounds.centerY()}"
            else return ToolResult.fail("Could not tap \"${label(e)}\".")
            return ScreenSession.observe(svc, before, since, "Tapped \"${label(e)}\" ($how).")
        }
    },

    object : Tool(
        "tap_text",
        "Tap the first visible element whose text or description matches. Use when you know the label but not the id.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { string("text", "Visible label to tap"); bool("exact", "Require exact (case-insensitive) match") },
    ) {
        override fun describe(args: Args) = "Tapping \"${args.str("text")}\""
        override fun assess(args: Args) =
            SensitiveUi.classify(args.str("text").orEmpty(), ScreenSession.last?.packageName ?: ScreenSession.svc()?.foregroundPackage.orEmpty())
                ?: RiskAssessment(Risk.LOW, describe(args))
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val raw = args.str("text")!!.trim()
            val q = raw.lowercase()
            val exact = args.bool("exact") == true
            fun m(s: String) = if (exact) s.lowercase() == q else s.lowercase().contains(q)
            // The model may not have read the screen first, so compare against the screen as it is now (reused if no event arrived).
            val before = ScreenSession.fresh(svc, 600).obs
            // Targeted lookup first: one app-side search instead of walking the whole tree.
            var e = ScreenReader.findByText(svc, raw).filter { m(it.text) || m(it.desc) }.maxByOrNull { score(it, q) }
            if (e == null) {
                val snap = ScreenSession.read(svc)
                e = snap.elements.filter { m(it.text) || m(it.desc) }.maxByOrNull { score(it, q) }
                    ?: return ToolResult.fail("No element with text \"$q\" on screen.\n" + snap.renderCompact(60))
            }
            UiFeedback.focus(e.bounds)
            val since = svc.gate.version
            if (!ScreenReader.clickNode(e.node)) {
                if (!svc.tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) return ToolResult.fail("Tap failed.")
            } else UiFeedback.showTap(e.bounds.exactCenterX(), e.bounds.exactCenterY())
            return ScreenSession.observe(svc, before, since, "Tapped \"${label(e)}\".")
        }
    },

    object : Tool(
        "long_press_element",
        "Long-press an element by id (opens context menus, selection, etc.).",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { int("element_id", "Element id") },
    ) {
        override fun describe(args: Args) = ScreenSession.element(args.int("element_id"))?.let { "Long-pressing \"${label(it)}\"" } ?: "Long-pressing"
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val e0 = ScreenSession.element(args.int("element_id")) ?: return ToolResult.fail("Unknown element id. Call read_screen first.")
            val before = ScreenSession.last?.obs
            val e = ScreenReader.resolve(e0) { ScreenSession.read(svc, ScreenReader.OBSERVE_BUDGET_MS) } ?: return ToolResult.fail("Element is no longer on screen.")
            UiFeedback.focus(e.bounds)
            val since = svc.gate.version
            val ok = ScreenReader.longClickNode(e.node) || svc.longPress(e.bounds.exactCenterX(), e.bounds.exactCenterY())
            if (!ok) return ToolResult.fail("Long press failed.")
            return ScreenSession.observe(svc, before, since, "Long-pressed \"${label(e)}\".")
        }
    },

    object : Tool(
        "type_text",
        "Type text into an input field (replaces its content). Uses element_id if given, otherwise the focused field, otherwise the first visible input. submit=true presses the keyboard's enter/search action (good for search boxes). In chat apps do NOT use submit; tap the Send button instead.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { string("text", "Text to enter"); int("element_id", "Input element id", required = false); bool("submit", "Press enter/search after typing") },
    ) {
        override fun describe(args: Args) = "Typing \"${args.str("text")?.take(40)}\""
        override fun assess(args: Args): RiskAssessment {
            val pkg = ScreenSession.last?.packageName.orEmpty()
            if (SensitiveUi.isFinancial(pkg)) return RiskAssessment(Risk.HIGH, "Type into a financial app ($pkg)")
            // Submitting a message is a send: preview the exact text and ask. Search boxes stay low risk.
            val isSearch = ScreenSession.last?.elements?.any { it.editable && (it.text + it.desc + it.viewId).contains("search", true) } == true
            return if (args.bool("submit") == true && !isSearch) RiskAssessment(Risk.MEDIUM, "Type and send \"${args.str("text")?.take(200)}\"")
            else RiskAssessment(Risk.LOW, describe(args))
        }
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val text = args.str("text") ?: ""
            val snap = ScreenSession.fresh(svc, 400)
            val before = snap.obs
            val target = ScreenSession.element(args.int("element_id"))?.let { ScreenReader.resolve(it) { snap } }?.node
                ?: ScreenReader.focusedInput(svc)
                ?: snap.elements.firstOrNull { it.editable }?.node
                ?: return ToolResult.fail("No input field found on screen. Tap the search/input control first.")
            if (target.isPassword) return ToolResult.fail("NYRO never types into password fields. Ask the user to enter it themselves.")
            val previousText = target.text?.toString().orEmpty()
            val r = android.graphics.Rect().also { target.getBoundsInScreen(it) }
            UiFeedback.focus(r)
            UiFeedback.typing(r)
            val since = svc.gate.version
            if (!ScreenReader.setText(target, text)) {
                // Some fields only accept text after a tap gives them focus.
                svc.tap(r.exactCenterX(), r.exactCenterY()); svc.gate.awaitChange(since, 250)
                val focused = ScreenReader.focusedInput(svc) ?: target
                if (!ScreenReader.setText(focused, text)) return ToolResult.fail("The field did not accept text input.")
            }
            target.refresh()
            var actual = target.text?.toString().orEmpty()
            if (!(actual.contains(text.take(20)) || text.isEmpty())) {
                // The app may apply the text on its next frame: give it one event, not a fixed sleep.
                svc.gate.awaitChange(since, 250); target.refresh(); actual = target.text?.toString().orEmpty()
            }
            val verified = actual.contains(text.take(20)) || text.isEmpty()
            var note = if (verified) "Typed \"$text\"." else "Typed, but the field now shows \"${actual.take(60)}\" (may be formatted or autocompleted)."
            if (args.bool("submit") == true) {
                val sinceSubmit = svc.gate.version
                val accepted = ScreenReader.imeEnter(target)
                Perf.time(Metric.UI_WAIT) { svc.gate.awaitSettled(sinceSubmit, firstEventTimeoutMs = 400, quietMs = 120, maxMs = 1000) }
                target.refresh()
                val after = target.text?.toString().orEmpty()
                when {
                    !accepted -> note += " Keyboard submit action unavailable: NOT submitted. Tap the app's Send/Search button."
                    after.contains('\n') || after.trim() == text.trim() && text.isNotBlank() -> {
                        // Enter only inserted a newline (common in chat apps): undo it and say so.
                        if (after.contains('\n')) ScreenReader.setText(target, text)
                        note += " The keyboard action did NOT send/submit (text is still in the field). Tap the Send/Search button next."
                    }
                    else -> note += " Submitted with the keyboard action (field now: \"${after.take(40)}\")."
                }
            }
            return ScreenSession.observe(svc, before, since, note).copy(undo = {
                if (target.refresh()) ScreenReader.setText(target, previousText)
            })
        }
    },

    object : Tool(
        "scroll",
        "Scroll content. direction=down shows content further below. Uses the given scrollable element or the main scrollable area; falls back to a swipe.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { enum("direction", "Scroll direction", listOf("down", "up", "left", "right")); int("element_id", "Scrollable element id", required = false) },
    ) {
        override fun describe(args: Args) = "Scrolling ${args.str("direction")}"
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val dir = args.str("direction")!!
            val snap = ScreenSession.fresh(svc, 400)
            val before = snap.obs
            val target = ScreenSession.element(args.int("element_id"))?.let { ScreenReader.resolve(it) { snap } }
                ?: snap.elements.filter { it.scrollable }.maxByOrNull { it.bounds.width() * it.bounds.height() }
            var how = "accessibility scroll"
            val since = svc.gate.version
            if (target == null || !ScreenReader.scrollNode(target.node, dir)) {
                val w = snap.screenW.toFloat(); val h = snap.screenH.toFloat()
                val ok = when (dir) {
                    "down" -> svc.swipe(w / 2, h * 0.72f, w / 2, h * 0.30f)
                    "up" -> svc.swipe(w / 2, h * 0.30f, w / 2, h * 0.72f)
                    "left" -> svc.swipe(w * 0.2f, h / 2, w * 0.8f, h / 2)
                    else -> svc.swipe(w * 0.8f, h / 2, w * 0.2f, h / 2)
                }
                if (!ok) return ToolResult.fail("Scroll failed.")
                how = "swipe gesture"
            } else UiFeedback.focus(target.bounds)
            return ScreenSession.observe(svc, before, since, "Scrolled $dir ($how).")
        }
    },

    object : Tool(
        "swipe",
        "Perform a swipe gesture between two points in screen pixels (from read_screen coordinates). For lists prefer scroll.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { int("x1", "Start x"); int("y1", "Start y"); int("x2", "End x"); int("y2", "End y"); int("duration_ms", "Duration, default 350", required = false) },
    ) {
        override fun describe(args: Args) = "Swiping"
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val before = ScreenSession.fresh(svc, 400).obs
            val since = svc.gate.version
            val ok = svc.swipe(args.int("x1")!!.toFloat(), args.int("y1")!!.toFloat(), args.int("x2")!!.toFloat(), args.int("y2")!!.toFloat(), (args.int("duration_ms") ?: 350).toLong())
            if (!ok) return ToolResult.fail("Swipe was cancelled by the system.")
            return ScreenSession.observe(svc, before, since, "Swiped.")
        }
    },

    object : Tool(
        "tap_point",
        "Tap at coordinates. Use only when no element id fits (e.g. canvas/game UIs). space='screen' for read_screen coordinates, 'screenshot' for pixel coordinates in the latest screenshot image.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { int("x", "X"); int("y", "Y"); enum("space", "Coordinate space", listOf("screen", "screenshot"), required = false) },
    ) {
        override fun describe(args: Args) = "Tapping at ${args.int("x")},${args.int("y")}"
        override fun assess(args: Args): RiskAssessment {
            val pkg = ScreenSession.svc()?.foregroundPackage.orEmpty()
            return if (SensitiveUi.isFinancial(pkg)) RiskAssessment(Risk.HIGH, "Tap inside a financial app ($pkg)") else RiskAssessment(Risk.LOW, describe(args))
        }
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val scale = if (args.str("space") == "screenshot") ScreenSession.shotScale else 1f
            val x = args.int("x")!! / scale; val y = args.int("y")!! / scale
            val dm = svc.resources.displayMetrics
            if (x < 0 || y < 0 || x > dm.widthPixels || y > dm.heightPixels) return ToolResult.fail("Point is outside the screen (${dm.widthPixels}x${dm.heightPixels}).")
            val before = ScreenSession.fresh(svc, 400).obs
            val since = svc.gate.version
            if (!svc.tap(x, y)) return ToolResult.fail("Tap gesture was cancelled.")
            return ScreenSession.observe(svc, before, since, "Tapped at ${x.roundToInt()},${y.roundToInt()} (screen px).")
        }
    },

    globalTool("press_back", "Press the system Back button.", AccessibilityService.GLOBAL_ACTION_BACK, "Going back"),
    globalTool("press_home", "Go to the home screen.", AccessibilityService.GLOBAL_ACTION_HOME, "Going home"),
    globalTool("open_recents", "Open the recent apps overview (to switch apps).", AccessibilityService.GLOBAL_ACTION_RECENTS, "Opening recent apps"),
    globalTool("open_notification_shade", "Pull down the notification shade.", AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "Opening notifications"),
    globalTool("open_quick_settings", "Open Quick Settings tiles.", AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "Opening quick settings"),

    object : Tool(
        "lock_screen",
        "Lock the phone screen.",
        baseRisk = Risk.MEDIUM, requires = setOf(Requirement.ACCESSIBILITY),
    ) {
        override fun describe(args: Args) = "Locking the screen"
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            return if (svc.global(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)) ToolResult("Screen locked.") else ToolResult.fail("Lock action rejected.")
        }
    },

    object : Tool(
        "wait",
        "Wait up to the given seconds for content to load, then return the refreshed screen. Returns early once the screen changes and settles, or as soon as until_text is visible. Prefer until_text over a fixed time.",
        readOnly = true,
        params = { number("seconds", "Maximum wait, 0.5 to 8 seconds"); string("until_text", "Return as soon as this text is visible on screen", required = false) },
    ) {
        override fun describe(args: Args) = args.str("until_text")?.let { "Waiting for \"$it\"" } ?: "Waiting up to ${args.num("seconds")}s"
        override suspend fun run(args: Args): ToolResult {
            val maxMs = ((args.num("seconds") ?: 1.0).coerceIn(0.5, 8.0) * 1000).toLong()
            val svc = needSvc()
            if (svc == null) { delay(maxMs); return ToolResult("Waited.") }
            val until = args.str("until_text")?.lowercase()
            val since = svc.gate.version
            val t0 = RealClock.now()
            val note: String
            if (until != null) {
                val hit = Perf.time(Metric.UI_WAIT) {
                    svc.gate.awaitCondition(maxMs) {
                        val s = ScreenSession.read(svc, ScreenReader.OBSERVE_BUDGET_MS)
                        s.elements.any { it.text.lowercase().contains(until) || it.desc.lowercase().contains(until) }
                    }
                }
                note = if (hit) "\"$until\" is visible after ${RealClock.now() - t0} ms." else "\"$until\" did not appear within ${maxMs} ms."
            } else {
                Perf.time(Metric.UI_WAIT) {
                    if (svc.gate.awaitChange(since, maxMs)) {
                        svc.gate.awaitQuiet(quietMs = 300, maxMs = (maxMs - (RealClock.now() - t0)).coerceAtLeast(100))
                    }
                    Unit
                }
                note = "Waited ${RealClock.now() - t0} ms."
            }
            val obs = ScreenSession.read(svc, ScreenReader.OBSERVE_BUDGET_MS).obs
            return ToolResult(note + "\n" + ScreenSession.renderOnce(obs), obs = obs)
        }
    },

    object : Tool(
        "take_screenshot",
        "Capture the screen and look at it with vision. Use only when accessibility info is missing or insufficient (images, videos, games, custom-drawn UIs, visual layout questions). The image is sent to the configured Azure endpoint. An identical screen is not sent twice.",
        touchesScreen = true, readOnly = true,
        params = { string("question", "What you need to learn from the screenshot") },
    ) {
        override fun describe(args: Args) = "Looking at the screen (sharing screenshot with Azure)"
        override suspend fun run(args: Args): ToolResult {
            val g = NyroApp.graph
            if (!g.settings.current.allowScreenshots) return ToolResult.fail("Screenshots are disabled in NYRO settings. Use read_screen instead.")
            UiFeedback.scan()
            val raw = captureScreen() ?: return ToolResult.fail(lastCaptureError)
            // Numbered boxes on the elements, using the same ids as read_screen. The model picks a number
            // instead of estimating pixel coordinates from the picture.
            val snap = NyroAccessibilityService.current?.let { ScreenSession.fresh(it, 400) }
            val shot = renderShot(raw, snap)
            ScreenSession.shotScale = shot.scale
            if (ScreenSession.isDuplicateShot(shot.hash)) {
                return ToolResult("The screen looks identical to the screenshot you already have, so it was not sent again. Use the earlier image, or act and then look again.")
            }
            Perf.count(Metric.SCREENSHOTS_SENT, 1); Perf.count(Metric.IMAGE_BYTES, shot.bytes.toLong())
            UiFeedback.notice("Screen shared with Azure")
            val note = "Screenshot ${shot.w}x${shot.h}. Numbered boxes mark tappable elements: act on one with tap_element using its number (the same numbers as read_screen)."
            val cfg = g.settings.current.provider
            if (cfg.visionDeployment.isNotBlank() && cfg.visionDeployment != cfg.deployment) {
                return try {
                    val turn = g.ai.chat(cfg, listOf(
                        Msg.system("You describe Android phone screenshots precisely for an automation agent. Give element positions as pixel coordinates of this image."),
                        Msg.userWithImage(args.str("question") ?: "Describe this screen.", shot.base64)
                    ), deployment = cfg.visionDeployment)
                    ToolResult("$note\nVision analysis: ${turn.content}")
                } catch (e: Exception) { ToolResult.fail("Vision request failed: ${e.message}") }
            }
            return ToolResult(note, imageJpeg = shot.base64)
        }
    },
)

private fun globalTool(name: String, desc: String, action: Int, label: String) = object : Tool(name, desc, requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true) {
    override fun describe(args: Args) = label
    override suspend fun run(args: Args): ToolResult {
        val svc = needSvc() ?: return noSvc()
        val before = ScreenSession.last?.obs ?: ScreenSession.fresh(svc, 600).obs
        val since = svc.gate.version
        if (!svc.global(action)) return ToolResult.fail("System rejected the $name action.")
        return ScreenSession.observe(svc, before, since, "$label: done.")
    }
}

@Volatile private var lastCaptureError = "Screen capture unavailable."

/** Accessibility screenshot first (no extra consent, respects secure windows), MediaProjection otherwise. */
suspend fun captureScreen(): Bitmap? = Perf.time(Metric.SHOT_CAPTURE) {
    val svc = NyroAccessibilityService.current
    UiFeedback.hiddenForCapture {
        if (svc != null) {
            when (val r = svc.screenshot()) {
                is NyroAccessibilityService.Shot.Ok -> r.bitmap
                is NyroAccessibilityService.Shot.Failed -> { lastCaptureError = r.reason; null }
            }
        } else {
            ScreenCaptureManager.capture(NyroApp.graph.context).getOrElse { lastCaptureError = it.message ?: "Capture failed"; null }
        }
    }
}

/** A JPEG ready for the model, with a perceptual [hash] used to avoid sending the same picture twice. */
data class Shot(val base64: String, val scale: Float, val w: Int, val h: Int, val bytes: Int, val hash: Long)

/**
 * Scales first, then draws the numbered boxes at the reduced size, then JPEG-encodes. The old path copied the full-resolution
 * ARGB bitmap (about 10 MB on this phone), drew on that, and only then scaled it down.
 */
fun renderShot(src: Bitmap, snap: ScreenSnapshot?, maxDim: Int = 720, quality: Int = 50): Shot = Perf.time(Metric.SHOT_ENCODE) {
    val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
    val base = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true) else src
    val hash = run {
        val t = Bitmap.createScaledBitmap(base, 8, 8, true)
        val px = IntArray(64); t.getPixels(px, 0, 8, 0, 0, 8, 8)
        if (t !== base) t.recycle()
        ImageHash.average(IntArray(64) { ImageHash.gray(px[it]) })
    }
    val bmp = if (snap != null) annotate(base, snap, scale) else base
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    val bytes = out.toByteArray()
    val shot = Shot(Base64.encodeToString(bytes, Base64.NO_WRAP), scale, bmp.width, bmp.height, bytes.size, hash)
    if (bmp !== base) bmp.recycle()
    if (base !== src) base.recycle()
    src.recycle()
    shot
}

/** Draws numbered boxes on the tappable elements of [snap]. [scale] converts screen pixels to bitmap pixels. */
private fun annotate(src: Bitmap, snap: ScreenSnapshot, scale: Float): Bitmap {
    val out = src.copy(Bitmap.Config.ARGB_8888, true) ?: return src
    val canvas = android.graphics.Canvas(out)
    val accent = android.graphics.Color.rgb(74, 143, 224)
    val stroke = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE; strokeWidth = maxOf(1.5f, out.width / 300f); color = accent
    }
    val fill = android.graphics.Paint().apply { color = accent }
    val text = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE; textSize = maxOf(10f, out.width / 26f); typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    snap.elements.filter { it.clickable || it.editable || it.scrollable }.take(120).forEach { e ->
        val r = android.graphics.RectF(e.bounds.left * scale, e.bounds.top * scale, e.bounds.right * scale, e.bounds.bottom * scale)
        canvas.drawRect(r, stroke)
        val label = e.id.toString()
        val w = text.measureText(label) + 6f
        val h = text.textSize + 4f
        canvas.drawRect(r.left, r.top, r.left + w, r.top + h, fill)
        canvas.drawText(label, r.left + 3f, r.top + h - 4f, text)
    }
    return out
}

/** Kept for callers that already hold a Bitmap: annotate (when [snap] is given), scale and encode. */
data class Encoded(val base64: String, val scale: Float, val w: Int, val h: Int)

fun encode(src: Bitmap, maxDim: Int = 800, quality: Int = 60): Encoded =
    renderShot(src, null, maxDim, quality).let { Encoded(it.base64, it.scale, it.w, it.h) }
