package com.nyro.companion

import com.nyro.companion.exec.VisionCost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Luna counts 32 px patches and bills ceil(patches x 1.2). The first test reproduces the numbers published for
 * the patch-based family; the rest pin the ladder used by the encoder.
 */
class VisionCostTest {

    @Test
    fun patchCountingMatchesThePublishedExamples() {
        assertEquals("1024x1024", 1229, VisionCost.tokens(1024, 1024))
        assertEquals("1600x1600 is the 2,500 patch budget", 3000, VisionCost.tokens(1600, 1600))
        assertEquals("4096x512, 2048 patches", 2458, VisionCost.tokens(4096, 512))
    }

    @Test
    fun theOldFixedCapWasCheapAndUnreadable() {
        // 324x720 is what the old 720 px cap produced for a 1080x2400 screen: 304 tokens at ~0.3x scale.
        assertEquals(304, VisionCost.tokens(324, 720))
    }

    @Test
    fun dimensionsSnapToWholePatches() {
        val fit = VisionCost.fit(1080, 700, VisionCost.Detail.HIGH)
        assertEquals(0, fit.width % VisionCost.PATCH)
        assertEquals(0, fit.height % VisionCost.PATCH)
        assertEquals(832, fit.tokens)
        assertTrue("never enlarges", fit.width <= 1080 && fit.height <= 700)
    }

    @Test
    fun everyLevelStaysInsideItsBudget() {
        for (level in VisionCost.Level.entries) {
            val fit = VisionCost.plan(1080, 2400, level)
            assertTrue("${level.label} costs ${fit.tokens} > ${level.maxTokens}", fit.tokens <= level.maxTokens)
            assertTrue("${level.label} is $fit", fit.width > 0 && fit.height > 0)
        }
    }

    @Test
    fun aNativeCropIsLegibleAndCheaperThanAFullScreen() {
        val crop = VisionCost.plan(1080, 700, VisionCost.Level.CROP)
        val full = VisionCost.plan(1080, 2400, VisionCost.Level.FULL)
        assertTrue("crop ${crop.tokens}", crop.tokens < 1000)
        // The 2048 px side limit already shrinks a 1080x2400 frame to 921x2048, so the whole screen is ~2.2k.
        assertTrue("full ${full.tokens}", full.tokens in 2000..3100)
        assertTrue("the crop keeps (almost) native width: ${crop.width}", crop.width >= 1000)
    }

    @Test
    fun thumbnailsAreMuchCheaperThanOverviews() {
        val thumb = VisionCost.plan(1080, 2400, VisionCost.Level.THUMB)
        val overview = VisionCost.plan(1080, 2400, VisionCost.Level.OVERVIEW)
        assertTrue("thumb ${thumb.tokens}", thumb.tokens < 200)
        assertTrue("overview ${overview.tokens}", overview.tokens in 1000..1400)
    }

    @Test
    fun aLowDetailThumbnailFitsThe512Box() {
        val fit = VisionCost.fit(1080, 2400, VisionCost.Detail.LOW)
        assertTrue("longest side $fit", maxOf(fit.width, fit.height) <= 512)
    }
}
