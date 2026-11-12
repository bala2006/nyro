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
import com.nyro.companion.overlay.UiFeedback
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/** Shared state between screen tools within a task: the snapshot that element ids refer to. */
object ScreenSession {
    @Volatile var last: ScreenSnapshot? = null
    /** Screenshot pixels per screen pixel for the latest screenshot. */
    @Volatile var shotScale: Float = 1f

    fun svc(): NyroAccessibilityService? = NyroAccessibilityService.current

    fun read(svc: NyroAccessibilityService): ScreenSnapshot =
        ScreenReader.snapshot(svc) { NyroApp.graph.apps.label(it) }.also { last = it }

    /** Waits for the UI to settle and reports what changed so the model can verify its action. */
    suspend fun observe(svc: NyroAccessibilityService, beforeSig: Int?, actionNote: String, maxElements: Int = 90): ToolResult {
        svc.waitForIdle()
        val snap = read(svc)
        val changed = beforeSig == null || beforeSig != snap.signature
        return ToolResult(buildString {
            append(actionNote).append('\n')
            append("Screen changed: ").append(if (changed) "yes" else "NO (the action may not have had a visible effect)").append('\n')
            append(snap.render(maxElements))
        })
    }

    fun element(id: Int?): UiElement? = id?.let { i -> last?.elements?.firstOrNull { it.id == i } }
}

private fun needSvc(): NyroAccessibilityService? = ScreenSession.svc()
private fun noSvc() = ToolResult.fail(Requirement.ACCESSIBILITY.label)

private fun label(e: UiElement) = e.text.ifBlank { e.desc }.ifBlank { e.viewId }.ifBlank { e.role }

fun screenTools(): List<Tool> = listOf(
    object : Tool(
        "read_screen",
        "Read the current screen through accessibility: app, title and a numbered list of visible elements with roles, text, flags and center coordinates. Use before interacting and after actions when unsure. Element ids are valid until the screen changes.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
    ) {
        override fun describe(args: Args) = "Reading the screen"
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            svc.waitForIdle(quietMs = 250, timeoutMs = 1500)
            return ToolResult(ScreenSession.read(svc).render(140))
        }
    },

    object : Tool(
        "find_elements",
        "Search the current screen for elements whose text, description or resource id contains the query (case-insensitive).",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { string("query", "Text to look for") },
    ) {
        override fun describe(args: Args) = "Looking for \"${args.str("query")}\""
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val q = args.str("query")!!.lowercase()
            val snap = ScreenSession.read(svc)
            val hits = snap.elements.filter { (it.text + " " + it.desc + " " + it.viewId).lowercase().contains(q) }
            if (hits.isEmpty()) return ToolResult("No element matching \"$q\" on ${snap.appLabel}. ${snap.elements.size} elements visible; consider scrolling or take_screenshot.")
            return ToolResult(snap.copy(elements = hits).render(40))
        }
    },

    object : Tool(
        "tap_element",
        "Tap an element by its id from the latest read_screen/find_elements/action result. Preferred over coordinates.",
        requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true,
        params = { int("element_id", "Element id, e.g. 12") },
    ) {
        override fun describe(args: Args) = ScreenSession.element(args.int("element_id"))?.let { "Tapping \"${label(it)}\"" } ?: "Tapping element ${args.int("element_id")}"
        override fun assess(args: Args): RiskAssessment {
            val e = ScreenSession.element(args.int("element_id")) ?: return RiskAssessment(Risk.LOW, describe(args))
            return SensitiveUi.classify(label(e), ScreenSession.last?.packageName.orEmpty(), e.checked != null) ?: RiskAssessment(Risk.LOW, describe(args))
        }
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val e0 = ScreenSession.element(args.int("element_id")) ?: return ToolResult.fail("Unknown element id. Call read_screen first.")
            val before = ScreenSession.last?.signature
            val e = ScreenReader.resolve(e0, ScreenSession.read(svc)) ?: return ToolResult.fail("Element \"${label(e0)}\" is no longer on screen. Read the screen again.")
            if (!e.enabled) return ToolResult.fail("Element \"${label(e)}\" is disabled.")
            UiFeedback.highlight(e.bounds)
            val how = if (ScreenReader.clickNode(e.node)) {
                UiFeedback.showTap(e.bounds.exactCenterX(), e.bounds.exactCenterY()); "accessibility click"
            } else if (svc.tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) "gesture tap at ${e.bounds.centerX()},${e.bounds.centerY()}"
            else return ToolResult.fail("Could not tap \"${label(e)}\".")
            return ScreenSession.observe(svc, before, "Tapped \"${label(e)}\" ($how).")
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
            val q = args.str("text")!!.trim().lowercase()
            val exact = args.bool("exact") == true
            val snap = ScreenSession.read(svc)
            val before = snap.signature
            fun m(s: String) = if (exact) s.lowercase() == q else s.lowercase().contains(q)
            val e = snap.elements.filter { m(it.text) || m(it.desc) }
                .sortedWith(compareByDescending<UiElement> { it.text.equals(q, true) || it.desc.equals(q, true) }.thenByDescending { it.clickable })
                .firstOrNull() ?: return ToolResult.fail("No element with text \"$q\" on screen.\n" + snap.render(60))
            UiFeedback.highlight(e.bounds)
            if (!ScreenReader.clickNode(e.node)) {
                if (!svc.tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())) return ToolResult.fail("Tap failed.")
            } else UiFeedback.showTap(e.bounds.exactCenterX(), e.bounds.exactCenterY())
            return ScreenSession.observe(svc, before, "Tapped \"${label(e)}\".")
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
            val before = ScreenSession.last?.signature
            val e = ScreenReader.resolve(e0, ScreenSession.read(svc)) ?: return ToolResult.fail("Element is no longer on screen.")
            UiFeedback.highlight(e.bounds)
            val ok = ScreenReader.longClickNode(e.node) || svc.longPress(e.bounds.exactCenterX(), e.bounds.exactCenterY())
            if (!ok) return ToolResult.fail("Long press failed.")
            return ScreenSession.observe(svc, before, "Long-pressed \"${label(e)}\".")
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
            return if (args.bool("submit") == true) RiskAssessment(Risk.LOW, describe(args) + " and submit") else RiskAssessment(Risk.LOW, describe(args))
        }
        override suspend fun run(args: Args): ToolResult {
            val svc = needSvc() ?: return noSvc()
            val text = args.str("text") ?: ""
            val snap = ScreenSession.read(svc)
            val before = snap.signature
            val target = ScreenSession.element(args.int("element_id"))?.let { ScreenReader.resolve(it, snap) }?.node
                ?: ScreenReader.focusedInput(svc)
                ?: snap.elements.firstOrNull { it.editable }?.node
                ?: return ToolResult.fail("No input field found on screen. Tap the search/input control first.")
            if (target.isPassword) return ToolResult.fail("NYRO never types into password fields. Ask the user to enter it themselves.")
            val r = android.graphics.Rect().also { target.getBoundsInScreen(it) }
            UiFeedback.highlight(r)
            if (!ScreenReader.setText(target, text)) {
                // Some fields only accept text after a tap gives them focus.
                svc.tap(r.exactCenterX(), r.exactCenterY()); delay(300)
                val focused = ScreenReader.focusedInput(svc) ?: target
                if (!ScreenReader.setText(focused, text)) return ToolResult.fail("The field did not accept text input.")
            }
            delay(150)
            target.refresh()
            val actual = target.text?.toString().orEmpty()
            val verified = actual.contains(text.take(20)) || text.isEmpty()
            var note = if (verified) "Typed \"$text\"." else "Typed, but the field now shows \"${actual.take(60)}\" (may be formatted or autocompleted)."
            if (args.bool("submit") == true) {
                val accepted = ScreenReader.imeEnter(target)
                delay(400)
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
            return ScreenSession.observe(svc, before, note)
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
            val snap = ScreenSession.read(svc)
            val before = snap.signature
            val target = ScreenSession.element(args.int("element_id"))?.let { ScreenReader.resolve(it, snap) }
                ?: snap.elements.filter { it.scrollable }.maxByOrNull { it.bounds.width() * it.bounds.height() }
            var how = "accessibility scroll"
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
            } else UiFeedback.highlight(target.bounds)
            return ScreenSession.observe(svc, before, "Scrolled $dir ($how).")
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
            val before = ScreenSession.read(svc).signature
            val ok = svc.swipe(args.int("x1")!!.toFloat(), args.int("y1")!!.toFloat(), args.int("x2")!!.toFloat(), args.int("y2")!!.toFloat(), (args.int("duration_ms") ?: 350).toLong())
            if (!ok) return ToolResult.fail("Swipe was cancelled by the system.")
            return ScreenSession.observe(svc, before, "Swiped.")
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
            val before = ScreenSession.read(svc).signature
            if (!svc.tap(x, y)) return ToolResult.fail("Tap gesture was cancelled.")
            return ScreenSession.observe(svc, before, "Tapped at ${x.roundToInt()},${y.roundToInt()} (screen px).")
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
        "Wait for content to load, then return the refreshed screen.",
        params = { number("seconds", "0.5 to 8 seconds") },
    ) {
        override fun describe(args: Args) = "Waiting ${args.num("seconds")}s"
        override suspend fun run(args: Args): ToolResult {
            delay(((args.num("seconds") ?: 1.0).coerceIn(0.5, 8.0) * 1000).toLong())
            val svc = needSvc() ?: return ToolResult("Waited.")
            return ToolResult("Waited.\n" + ScreenSession.read(svc).render(90))
        }
    },

    object : Tool(
        "take_screenshot",
        "Capture the screen and look at it with vision. Use when accessibility info is missing or insufficient (images, videos, games, custom-drawn UIs, visual layout questions). The image is sent to the configured Azure endpoint.",
        touchesScreen = true,
        params = { string("question", "What you need to learn from the screenshot") },
    ) {
        override fun describe(args: Args) = "Looking at the screen (sharing screenshot with Azure)"
        override suspend fun run(args: Args): ToolResult {
            val g = NyroApp.graph
            if (!g.settings.current.allowScreenshots) return ToolResult.fail("Screenshots are disabled in NYRO settings. Use read_screen instead.")
            val bmp = captureScreen() ?: return ToolResult.fail(lastCaptureError)
            val (jpeg, scale, w, h) = encode(bmp)
            ScreenSession.shotScale = scale
            UiFeedback.notice("Screen shared with Azure")
            val note = "Screenshot ${w}x$h (scale $scale of screen pixels). Use tap_point with space='screenshot' for image coordinates."
            val cfg = g.settings.current.provider
            if (cfg.visionDeployment.isNotBlank() && cfg.visionDeployment != cfg.deployment) {
                return try {
                    val turn = g.ai.chat(cfg, listOf(
                        Msg.system("You describe Android phone screenshots precisely for an automation agent. Give element positions as pixel coordinates of this image."),
                        Msg.userWithImage(args.str("question") ?: "Describe this screen.", jpeg)
                    ), deployment = cfg.visionDeployment)
                    ToolResult("$note\nVision analysis: ${turn.content}")
                } catch (e: Exception) { ToolResult.fail("Vision request failed: ${e.message}") }
            }
            return ToolResult(note, imageJpeg = jpeg)
        }
    },
)

private fun globalTool(name: String, desc: String, action: Int, label: String) = object : Tool(name, desc, requires = setOf(Requirement.ACCESSIBILITY), touchesScreen = true) {
    override fun describe(args: Args) = label
    override suspend fun run(args: Args): ToolResult {
        val svc = needSvc() ?: return noSvc()
        val before = ScreenSession.read(svc).signature
        if (!svc.global(action)) return ToolResult.fail("System rejected the $name action.")
        return ScreenSession.observe(svc, before, "$label: done.", maxElements = 60)
    }
}

@Volatile private var lastCaptureError = "Screen capture unavailable."

/** Accessibility screenshot first (no extra consent, respects secure windows), MediaProjection otherwise. */
suspend fun captureScreen(): Bitmap? {
    val svc = NyroAccessibilityService.current
    return UiFeedback.hiddenForCapture {
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

data class Encoded(val base64: String, val scale: Float, val w: Int, val h: Int)

fun encode(src: Bitmap, maxDim: Int = 1280, quality: Int = 72): Encoded {
    val scale = minOf(1f, maxDim.toFloat() / maxOf(src.width, src.height))
    val bmp = if (scale < 1f) Bitmap.createScaledBitmap(src, (src.width * scale).roundToInt(), (src.height * scale).roundToInt(), true) else src
    val out = ByteArrayOutputStream()
    bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)
    val e = Encoded(Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP), scale, bmp.width, bmp.height)
    if (bmp !== src) bmp.recycle()
    src.recycle()
    return e
}
