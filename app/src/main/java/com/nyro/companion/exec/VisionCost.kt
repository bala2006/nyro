package com.nyro.companion.exec

import kotlin.math.ceil

/**
 * What one picture costs, in image tokens, for the deployment NYRO talks to: Azure `gpt-6-luna`.
 *
 * Luna sizes images by 32 px patches and bills `ceil(patches x 1.2)`, so cost is proportional to area.
 * Text needs roughly one patch per line height: a 12 sp label on a 1080 px wide phone is about 30 px tall,
 * which survives a 0.7x scale and turns to mush below ~0.5x. Two consequences shape the whole design:
 *
 * 1. A downscaled full screen is the worst of both worlds - cheap, but unreadable. (The old 720 px cap made a
 *    324x720 image for 304 tokens that could not be read.)
 * 2. A native-resolution crop of the part that matters is legible for a fraction of a full screen's cost.
 *
 * `detail: original` is never sent. Luna reads `auto` as `original`, which has a 10,000-patch budget: neither
 * bounded nor cheaper. On a phone screen `high` is enough - a 1080x2400 frame is resized to 921x2048 by the
 * 2048 px side limit (2,228 tokens at 0.85x scale), while `original` would send 2,550 patches unshrunk for
 * about 3,060 tokens and no visible gain.
 */
object VisionCost {
    const val PATCH = 32
    const val MULTIPLIER = 1.2

    /** The only two levels NYRO sends. */
    enum class Detail(val wire: String, val maxSide: Int, val maxPatches: Int) {
        LOW("low", 512, 256),
        HIGH("high", 2048, 2500),
    }

    /**
     * How much of the screen one picture covers. [maxTokens] is a ceiling the encoder enforces, so an
     * unexpected screen size can never produce an unexpectedly large request.
     */
    enum class Level(val label: String, val maxTokens: Int, val detail: Detail) {
        /** Layout only: the cheapest way to see what a screen looks like. */
        THUMB("layout thumbnail", 220, Detail.LOW),

        /** Readable layout: body text legible, small labels are not. */
        OVERVIEW("readable overview", 1_300, Detail.HIGH),

        /** Native-resolution crop of the content area or one element: everything inside stays legible. */
        CROP("native crop", 1_100, Detail.HIGH),

        /** The whole screen. Only when the whole layout is the question. */
        FULL("full screen", 3_000, Detail.HIGH),
    }

    data class Fit(val width: Int, val height: Int) {
        val patches: Int get() = VisionCost.patches(width, height)
        val tokens: Int get() = VisionCost.tokens(width, height)
        override fun toString(): String = "${width}x$height"
    }

    fun patches(width: Int, height: Int): Int = ceilDiv(width, PATCH) * ceilDiv(height, PATCH)

    fun tokens(width: Int, height: Int): Int = ceil(patches(width, height) * MULTIPLIER).toInt()

    /** Largest size [detail] accepts for a width x height picture. Never enlarges; snaps to whole patches. */
    fun fit(width: Int, height: Int, detail: Detail, snap: Boolean = true): Fit {
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        val box = detail.maxSide.toFloat() / maxOf(w, h)
        if (box < 1f) { w = (w * box).toInt(); h = (h * box).toInt() }
        if (patches(w, h) > detail.maxPatches) {
            val s = scaleWithin(w, h, detail.maxPatches)
            w = (w * s).toInt().coerceAtLeast(PATCH)
            h = (h * s).toInt().coerceAtLeast(PATCH)
        }
        if (snap) { w = snapDown(w); h = snapDown(h) }
        // Rounding can leave one patch column over budget; drop whole patches until it fits.
        while (patches(w, h) > detail.maxPatches && maxOf(w, h) > PATCH) {
            if (h >= w) h = snapDown(h - PATCH) else w = snapDown(w - PATCH)
            w = w.coerceAtLeast(PATCH); h = h.coerceAtLeast(PATCH)
        }
        return Fit(w, h)
    }

    /** Largest size that stays within [maxTokens]; the detail level's own limits still apply. */
    fun fitToTokens(width: Int, height: Int, maxTokens: Int, detail: Detail = Detail.HIGH): Fit {
        val capped = fit(width, height, detail)
        if (capped.tokens <= maxTokens) return capped
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            val w = (capped.width * mid).toInt().coerceAtLeast(PATCH)
            val h = (capped.height * mid).toInt().coerceAtLeast(PATCH)
            if (tokens(w, h) <= maxTokens) lo = mid else hi = mid
        }
        return Fit(
            snapDown((capped.width * lo).toInt().coerceAtLeast(PATCH)),
            snapDown((capped.height * lo).toInt().coerceAtLeast(PATCH)),
        )
    }

    /** The picture size for one rung of the ladder: the smallest view that can still answer the question. */
    fun plan(screenWidth: Int, screenHeight: Int, level: Level): Fit = when (level) {
        // FULL keeps the frame as it is: `high` shrinks a 1080x2400 phone frame by about 2%, so nothing is lost.
        Level.FULL -> fit(screenWidth, screenHeight, level.detail, snap = false)
        else -> fitToTokens(screenWidth, screenHeight, level.maxTokens, level.detail)
    }

    /** One line for the tool result and the diagnostics card. */
    fun describe(level: Level, fit: Fit): String =
        "${level.label} ${fit.width}x${fit.height} = ${fit.tokens} image tokens"

    private fun snapDown(v: Int): Int = (v / PATCH).coerceAtLeast(1) * PATCH

    private fun ceilDiv(v: Int, by: Int): Int = (v + by - 1) / by

    /** Largest scale in (0,1] whose patch count fits [maxPatches]. */
    private fun scaleWithin(width: Int, height: Int, maxPatches: Int): Float {
        var lo = 0f
        var hi = 1f
        repeat(24) {
            val mid = (lo + hi) / 2f
            val w = (width * mid).toInt().coerceAtLeast(1)
            val h = (height * mid).toInt().coerceAtLeast(1)
            if (patches(w, h) <= maxPatches) lo = mid else hi = mid
        }
        return lo
    }
}
