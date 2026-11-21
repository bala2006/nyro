package com.nyro.companion

import com.nyro.companion.exec.VisionCost
import com.nyro.companion.exec.VisionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class VisionPolicyTest {

    @Test
    fun aScreenTheTreeExplainsNeedsNoPicture() {
        assertNull(VisionPolicy.forAuto(allowed = true, elements = 40, interactive = 12))
    }

    @Test
    fun canvasScreensFallBackToAReadableOverview() {
        val decision = VisionPolicy.forAuto(allowed = true, elements = 0, interactive = 0)
        assertNotNull(decision)
        assertEquals(VisionCost.Level.OVERVIEW, decision!!.level)
        assertEquals(VisionCost.Level.OVERVIEW, VisionPolicy.forAuto(true, 8, 0)!!.level)
    }

    @Test
    fun disabledScreenshotsNeverAttachAnything() {
        assertNull(VisionPolicy.forAuto(allowed = false, elements = 0, interactive = 0))
        assertNull(VisionPolicy.forTool(allowed = false, question = "read the price", elements = 40, interactive = 8, target = false))
    }

    @Test
    fun aReadingQuestionGetsTheContentCropNotTheWholeScreen() {
        val decision = VisionPolicy.forTool(true, "what does the total say?", 40, 8, target = false)
        assertEquals(VisionCost.Level.CROP, decision!!.level)
    }

    @Test
    fun aNamedElementIsCroppedToThatElement() {
        val decision = VisionPolicy.forTool(true, "what is in this field?", 40, 8, target = true)
        assertEquals(VisionCost.Level.CROP, decision!!.level)
        assertEquals("cropped to the element you named", decision.reason)
    }

    @Test
    fun onlyAnExplicitWholeScreenQuestionBuysTheFullScreen() {
        for (q in listOf("describe the whole screen", "show me the layout", "what's on screen right now")) {
            assertEquals(q, VisionCost.Level.FULL, VisionPolicy.forTool(true, q, 40, 8, false)!!.level)
        }
    }

    @Test
    fun anEmptyTreeGetsTheOverviewEvenWhenTheModelAsksForACrop() {
        val decision = VisionPolicy.forTool(true, "read the small label", 0, 0, target = false)
        assertEquals(VisionCost.Level.OVERVIEW, decision!!.level)
    }
}
