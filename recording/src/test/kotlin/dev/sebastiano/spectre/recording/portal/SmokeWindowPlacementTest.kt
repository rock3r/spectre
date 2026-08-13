package dev.sebastiano.spectre.recording.portal

import java.awt.Dimension
import java.awt.Insets
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SmokeWindowPlacementTest {
    @Test
    fun `visible screen shrinks by insets`() {
        val visible =
            SmokeWindowPlacement.visibleScreenBounds(
                screen = Rectangle(0, 0, 1536, 864),
                insets = Insets(32, 0, 0, 0),
            )
        assertEquals(Rectangle(0, 32, 1536, 832), visible)
    }

    @Test
    fun `centered 480x240 window stays inside a 1536-wide screen`() {
        val screen = Rectangle(0, 0, 1536, 864)
        val placed = SmokeWindowPlacement.placedBounds(screen, Dimension(480, 240))
        assertEquals(Rectangle(528, 312, 480, 240), placed)
        assertTrue(screen.contains(placed), "placed=$placed screen=$screen")
    }

    @Test
    fun `window larger than the screen is clamped to the visible area`() {
        val screen = Rectangle(0, 0, 800, 600)
        val placed = SmokeWindowPlacement.placedBounds(screen, Dimension(2000, 1800))
        assertEquals(screen, placed)
    }
}
