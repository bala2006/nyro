package com.nyro.companion.tools

import com.nyro.companion.NyroApp
import com.nyro.companion.access.NyroAccessibilityService
import com.nyro.companion.access.ScreenReader
import com.nyro.companion.access.ScreenSnapshot
import com.nyro.companion.access.UiElement
import com.nyro.companion.overlay.UiFeedback
import kotlinx.coroutines.delay

/**
 * App-specific skills: fixed, verified steps for one app. The model chooses the skill and the
 * arguments; the steps themselves are code, so they don't depend on the model guessing taps.
 */
fun skillTools(): List<Tool> = listOf(
    object : Tool(
        "send_whatsapp_message",
        "Send a WhatsApp message to a contact by name. Opens WhatsApp, searches the contact, types the message, taps Send and checks that it appears in the chat. Use this for any WhatsApp message.",
        baseRisk = Risk.MEDIUM,
        requires = setOf(Requirement.ACCESSIBILITY),
        params = {
            string("contact", "Contact name as saved on the phone")
            string("text", "Message to send")
        },
    ) {
        override fun describe(args: Args) = "Send WhatsApp to ${args.str("contact")}"
        override fun assess(args: Args) = RiskAssessment(
            Risk.MEDIUM,
            "Send a WhatsApp message to ${args.str("contact")}: \"${args.str("text")?.take(200)}\"",
        )
        override suspend fun run(args: Args): ToolResult =
            whatsappSend(args.str("contact")!!, args.str("text")!!)
    },
)

private const val WA = "com.whatsapp"

private suspend fun whatsappSend(contact: String, text: String): ToolResult {
    val svc = NyroAccessibilityService.current ?: return ToolResult.fail(Requirement.ACCESSIBILITY.label)
    if (!NyroApp.graph.apps.launch(WA)) return ToolResult.fail("WhatsApp is not installed or cannot be opened.")

    // 1. WhatsApp in front
    settle(svc, 5000) { it.packageName == WA }
        ?: return ToolResult.fail("WhatsApp did not come to the front.")

    // 2. open search (the chats screen has a Search control in its top bar)
    val search = ScreenSession.read(svc).elements.firstOrNull { e ->
        e.clickable && (e.desc.contains("Search", true) || e.text.equals("Search", true))
    } ?: return ToolResult.fail("Search control not found in WhatsApp. Open the Chats tab and try again.")
    tap(svc, search)

    // 3. type the contact name into the search field
    val field = settle(svc, 4000) { s -> s.elements.any { it.editable } }
        ?.elements?.firstOrNull { it.editable }
        ?: return ToolResult.fail("WhatsApp search field did not open.")
    if (!ScreenReader.setText(field.node, contact)) return ToolResult.fail("Could not type the contact name.")

    // 4. choose the matching chat row
    val row = settle(svc, 4000) { s ->
        s.elements.any { e -> e.clickable && !e.editable && (e.text.equals(contact, true) || e.desc.equals(contact, true)) }
    }?.elements?.filter { e -> e.clickable && !e.editable && (e.text.equals(contact, true) || e.desc.equals(contact, true)) }
        ?.firstOrNull() ?: return ToolResult.fail("No WhatsApp chat named \"$contact\". Check the name as saved on this phone.")
    tap(svc, row)

    // 5. the chat screen has a message box
    val box = settle(svc, 4000) { s -> s.elements.any { it.editable && !it.text.contains("Search", true) } }
        ?.elements?.firstOrNull { it.editable && !it.text.contains("Search", true) }
        ?: return ToolResult.fail("The chat with $contact did not open.")
    if (!ScreenReader.setText(box.node, text)) return ToolResult.fail("Could not type the message.")

    // 6. press Send
    val send = settle(svc, 2000) { s -> s.elements.any { it.clickable && it.desc.equals("Send", true) } }
        ?.elements?.firstOrNull { it.clickable && it.desc.equals("Send", true) }
        ?: return ToolResult.fail("The message is typed but the Send button was not found. It was not sent.")
    tap(svc, send)

    // 7. verify: the message shows in the chat and the message box is empty again
    val sentSnap = settle(svc, 5000) { s ->
        s.elements.any { it.text.contains(text.take(25)) && !it.editable } &&
            s.elements.none { it.editable && it.text.contains(text.take(15)) }
    }
    return if (sentSnap != null) {
        ToolResult("Sent to $contact: \"$text\".")
    } else {
        ToolResult("Send was tapped, but I could not confirm the message in the chat. Check the screen before doing anything else.", ok = false)
    }
}

/** Polls the screen until [ready] is true or time runs out. Returns the snapshot that matched. */
private suspend fun settle(svc: NyroAccessibilityService, timeoutMs: Long, ready: (ScreenSnapshot) -> Boolean): ScreenSnapshot? {
    val start = System.currentTimeMillis()
    while (System.currentTimeMillis() - start < timeoutMs) {
        val snap = ScreenSession.read(svc)
        if (ready(snap)) return snap
        delay(250)
    }
    return null
}

private suspend fun tap(svc: NyroAccessibilityService, e: UiElement) {
    UiFeedback.focus(e.bounds)
    if (ScreenReader.clickNode(e.node)) {
        UiFeedback.showTap(e.bounds.exactCenterX(), e.bounds.exactCenterY())
    } else {
        svc.tap(e.bounds.exactCenterX(), e.bounds.exactCenterY())
    }
    svc.waitForIdle(quietMs = 250, timeoutMs = 2000)
}
