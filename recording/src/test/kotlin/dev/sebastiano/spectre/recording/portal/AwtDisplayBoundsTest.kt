package dev.sebastiano.spectre.recording.portal

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AwtDisplayBoundsTest {
    @Test
    fun `picks the display that contains the region`() {
        val primary = Rectangle(0, 0, 1920, 1080)
        val secondary = Rectangle(1920, 0, 2560, 1440)
        val bounds =
            awtDisplayBoundsContaining(
                region = Rectangle(2000, 100, 480, 240),
                displays = listOf(primary, secondary),
            )
        assertEquals(secondary, bounds)
    }

    @Test
    fun `returns null when the region misses every display`() {
        val bounds =
            awtDisplayBoundsContaining(
                region = Rectangle(9000, 0, 100, 100),
                displays = listOf(Rectangle(0, 0, 1920, 1080)),
            )
        assertNull(bounds)
    }
}
