package com.nyro.companion.exec

/** Tiny perceptual hash so an unchanged screen is not captured, encoded and uploaded to Azure again. */
object ImageHash {
    /** Average hash of an 8x8 grayscale thumbnail (64 values, 0..255). */
    fun average(gray: IntArray): Long {
        require(gray.size == 64) { "expected 8x8 thumbnail" }
        val mean = gray.sum() / 64
        var h = 0L
        for (i in 0 until 64) if (gray[i] >= mean) h = h or (1L shl i)
        return h
    }

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Bit differences up to this count are the same picture (compression and cursor-blink noise). */
    const val SAME_THRESHOLD = 2

    fun gray(argb: Int): Int {
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}

/**
 * Decides whether a screenshot is attached automatically. By default the accessibility tree is enough and nothing is
 * captured. A picture is attached only when the tree cannot describe the screen, and never twice for the same picture.
 */
object ScreenshotPolicy {
    data class Input(
        val allowedBySettings: Boolean,
        val accessibilityOn: Boolean,
        val elementCount: Int,
        val interactiveCount: Int,
        /** The model already captured one in this batch via take_screenshot. */
        val modelCapturedThisBatch: Boolean,
    )

    fun shouldAttachAutomatically(i: Input): Boolean {
        if (!i.allowedBySettings || !i.accessibilityOn) return false
        if (i.modelCapturedThisBatch) return false
        // Empty or non-interactive tree: canvas, game, video or a custom-drawn UI.
        return i.elementCount <= 2 || i.interactiveCount == 0
    }
}
