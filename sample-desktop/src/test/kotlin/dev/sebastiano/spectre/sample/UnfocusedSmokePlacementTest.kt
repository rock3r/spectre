package dev.sebastiano.spectre.sample

import androidx.compose.ui.geometry.Rect
import java.awt.Point
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    fun `overlapping distractor is moved to the right of the SUT`() {
        val sut = Rectangle(0, 0, 540, 320)
        val distractor = Rectangle(80, 80, 320, 200)
        assertTrue(sut.intersects(distractor))
        val separated = separateOverlappingBounds(sut, distractor, gap = 16)
        assertFalse(sut.intersects(separated))
        assertEquals(556, separated.x)
        assertEquals(80, separated.y)
        assertEquals(320, separated.width)
        assertEquals(200, separated.height)
    }

    @Test
    fun `already-apart windows keep their distractor bounds`() {
        val sut = Rectangle(600, 400, 540, 320)
        val distractor = Rectangle(80, 80, 320, 200)
        assertEquals(distractor, separateOverlappingBounds(sut, distractor, gap = 16))
    }

    @Test
    fun `composeCenterOnScreen at the origin matches the CI miss coordinate`() {
        val counter = Rect(left = 16f, top = 72f, right = 524f, bottom = 120f)
        val point =
            composeCenterOnScreen(counter, panelScreen = Point(0, 0), scaleX = 1f, scaleY = 1f)
        assertEquals(270, point.x)
        assertEquals(96, point.y)
    }

    @Test
    fun `unobscuredPointIn keeps the center when the distractor misses it`() {
        val counter = Rectangle(616, 472, 508, 48)
        val distractor = Rectangle(80, 80, 320, 200)
        assertEquals(Point(870, 496), unobscuredPointIn(counter, distractor))
    }

    @Test
    fun `unobscuredPointIn slides off a distractor covering the counter center`() {
        val counter = Rectangle(16, 72, 508, 48)
        val distractor = Rectangle(80, 80, 320, 200)
        val point = unobscuredPointIn(counter, distractor)
        assertTrue(counter.contains(point))
        assertFalse(distractor.contains(point))
    }
}
