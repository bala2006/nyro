package com.nyro.companion

import com.nyro.companion.overlay.Area
import com.nyro.companion.overlay.RoamPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoamPlannerTest {
    private val area = RoamPlanner.safeArea(1080, 2460, insetTop = 108, insetBottom = 120, density = 3f, keyboardOpen = false)

    @Test
    fun safeAreaKeepsClearOfStatusBarAndBottomBand() {
        assertTrue(area.top > 108)
        assertTrue(area.bottom < 2460 - 120)
    }

    @Test
    fun restSpotIsBottomRightInsideTheArea() {
        val (x, y) = RoamPlanner.restSpot(area, 200, 200)
        assertEquals(area.right - 200, x)
        assertEquals(area.bottom - 200, y)
    }

    @Test
    fun besideAFocusDoesNotCoverIt() {
        val focus = Area(300, 1000, 700, 1100)
        val (x, y) = RoamPlanner.beside(area, 200, 200, focus, gap = 30)
        assertFalse(Area(x, y, x + 200, y + 200).overlaps(focus))
    }

    @Test
    fun besideAFullScreenFocusStillStaysInside() {
        val focus = Area(0, 300, 1080, 2000)
        val (x, y) = RoamPlanner.beside(area, 200, 200, focus, gap = 30)
        assertTrue(x in area.left..(area.right - 200))
        assertTrue(y in area.top..(area.bottom - 200))
    }

    @Test
    fun besideEveryElementStaysInsideTheArea() {
        val elements = listOf(
            Area(0, 0, 60, 60), Area(1000, 2200, 1080, 2460), Area(400, 900, 680, 1100),
            Area(20, 1500, 1060, 1520), Area(540, 120, 1000, 260),
        )
        for (focus in elements) {
            val (x, y) = RoamPlanner.beside(area, 252, 252, focus, gap = 30)
            assertTrue("x out of area for $focus", x in area.left..(area.right - 252))
            assertTrue("y out of area for $focus", y in area.top..(area.bottom - 252))
        }
    }
}
