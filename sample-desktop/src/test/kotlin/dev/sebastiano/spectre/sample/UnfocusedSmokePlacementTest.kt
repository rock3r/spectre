package dev.sebastiano.spectre.sample

import androidx.compose.ui.geometry.Rect
import java.awt.Point
import kotlin.test.Test
import kotlin.test.assertEquals

class UnfocusedSmokePlacementTest {

    @Test
    fun `composeCenterOnScreen adds the panel origin so a local counter is not clicked at 0,0`() {
        val counter = Rect(left = 16f, top = 72f, right = 524f, bottom = 120f)
        val point =
            composeCenterOnScreen(counter, panelScreen = Point(600, 400), scaleX = 1f, scaleY = 1f)
        assertEquals(870, point.x)
        assertEquals(496, point.y)
    }

    @Test
    fun `composeCenterOnScreen at the origin matches the CI miss coordinate`() {
        val counter = Rect(left = 16f, top = 72f, right = 524f, bottom = 120f)
        val point =
            composeCenterOnScreen(counter, panelScreen = Point(0, 0), scaleX = 1f, scaleY = 1f)
        assertEquals(270, point.x)
        assertEquals(96, point.y)
    }
}
