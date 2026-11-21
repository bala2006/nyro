package com.nyro.companion.exec

/** Tiny perceptual hash, so an unchanged screen is not captured, encoded and uploaded again. */
object ImageHash {
    /** Bit differences up to this count are the same picture (compression and cursor-blink noise). */
    const val SAME_THRESHOLD = 2

    fun distance(a: Long, b: Long): Int = java.lang.Long.bitCount(a xor b)

    /** Average hash of an 8x8 grayscale thumbnail (64 values, 0..255). */
    fun average(gray: IntArray): Long {
        require(gray.size == 64) { "expected 8x8 thumbnail" }
        val mean = gray.sum() / 64
        var h = 0L
        for (i in 0 until 64) if (gray[i] >= mean) h = h or (1L shl i)
        return h
    }

    fun gray(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (r * 299 + g * 587 + b * 114) / 1000
    }
}
