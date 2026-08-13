package dev.sebastiano.spectre.recording.portal

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.SwingUtilities
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
    fun `placeOnVisibleScreen keeps a 480x240 window inside the screen`() {
        assumeDisplay()
        val frame = JFrame("placement")
        try {
            SwingUtilities.invokeAndWait {
                frame.isUndecorated = true
                SmokeWindowPlacement.placeOnVisibleScreen(frame, Dimension(480, 240))
                frame.pack()
            }
            val screen = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds
            val bounds = frame.bounds
            assertTrue(bounds.width <= 480, "width=${bounds.width}")
            assertTrue(bounds.height <= 240, "height=${bounds.height}")
            assertTrue(
                screen.contains(bounds) || intersectsFullyInside(screen, bounds),
                "placed=$bounds screen=$screen",
            )
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    private fun assumeDisplay() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Needs a display to place a JFrame",
        )
    }

    private fun intersectsFullyInside(screen: Rectangle, bounds: Rectangle): Boolean {
        val intersection = screen.intersection(bounds)
        return intersection == bounds && !intersection.isEmpty
    }
}
