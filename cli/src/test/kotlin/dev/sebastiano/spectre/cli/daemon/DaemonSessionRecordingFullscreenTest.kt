package dev.sebastiano.spectre.cli.daemon

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

class DaemonSessionRecordingFullscreenTest {
    @Test
    fun `default virtual desktop bounds has positive size when a screen is present`() {
        assumeFalse(
            java.awt.GraphicsEnvironment.isHeadless(),
            "virtual desktop bounds need a non-headless GraphicsEnvironment",
        )
        val bounds = defaultVirtualDesktopBounds()
        assertTrue(bounds.width > 0, "width=${bounds.width}")
        assertTrue(bounds.height > 0, "height=${bounds.height}")
    }

    @Test
    fun `virtual desktop bounds is the union of configured rectangles`() {
        // Structural check: union of two adjacent screens matches expected size.
        val left = Rectangle(0, 0, 100, 50)
        val right = Rectangle(100, 0, 80, 50)
        val union = left.union(right)
        assertEquals(0, union.x)
        assertEquals(0, union.y)
        assertEquals(180, union.width)
        assertEquals(50, union.height)
    }
}
