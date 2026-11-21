package com.nyro.companion.exec

/** One decision about a picture: how much of the screen to send, and why. The reason is shown to the model. */
data class VisionDecision(val level: VisionCost.Level, val reason: String)

/**
 * Chooses the smallest view that can answer the question.
 *
 * Text always comes first: the accessibility tree describes most screens for no image tokens at all. A picture
 * is attached only when the tree cannot describe the screen, and when the model asks for one it gets a crop of
 * the content area unless the whole layout is the question. That keeps screenshots both cheap and legible
 * instead of cheap and blurry.
 */
object VisionPolicy {
    private val wholeScreen = listOf(
        "whole screen", "full screen", "entire screen", "everything on", "all of it",
        "layout", "looks like", "what's on screen", "what is on screen", "whats on screen",
        "describe the screen", "picture of the screen",
    )

    private val reading = listOf(
        "read", "text", "label", "says", "say ", "number", "amount", "price", "date", "time",
        "error", "detail", "word", "sentence", "spell", "translate", "value",
    )

    /**
     * A picture is attached automatically only when the tree cannot describe the screen (canvas, game, video,
     * image viewer). A screen the tree explains never costs image tokens.
     */
    fun forAuto(allowed: Boolean, elements: Int, interactive: Int): VisionDecision? {
        if (!allowed) return null
        if (elements > 2 && interactive > 0) return null
        return VisionDecision(VisionCost.Level.OVERVIEW, "the accessibility tree has no usable elements on this screen")
    }

    /**
     * The model asked for a picture. Start at the content crop (legible and cheap) and cover the whole screen
     * only when the whole screen is what it asked about.
     */
    fun forTool(allowed: Boolean, question: String, elements: Int, interactive: Int, target: Boolean): VisionDecision? {
        if (!allowed) return null
        val q = question.lowercase()
        if (wholeScreen.any { q.contains(it) }) {
            return VisionDecision(VisionCost.Level.FULL, "you asked about the whole screen")
        }
        if (elements <= 2 || interactive == 0) {
            return VisionDecision(VisionCost.Level.OVERVIEW, "the accessibility tree has no usable elements here, so the layout is shown")
        }
        if (target) return VisionDecision(VisionCost.Level.CROP, "cropped to the element you named")
        if (reading.any { q.contains(it) }) {
            return VisionDecision(VisionCost.Level.CROP, "cropped to the content area so the text stays legible")
        }
        return VisionDecision(VisionCost.Level.CROP, "cropped to the content area")
    }
}
