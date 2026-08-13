package dev.sebastiano.spectre.recording.portal

import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.Rectangle
import javax.swing.JFrame

/**
 * Places a smoke [JFrame] fully inside the visible AWT screen, instead of blindly centering it.
 *
 * Fractional Wayland scale can make `setLocationRelativeTo(null)` put a 480×240 window so far into
 * logical coordinates that the portal stream (device pixels) no longer contains it.
 */
internal object SmokeWindowPlacement {
    fun placeOnVisibleScreen(frame: JFrame, preferredSize: Dimension) {
        val screen = visibleScreenBounds()
        val width = preferredSize.width.coerceAtMost(screen.width).coerceAtLeast(1)
        val height = preferredSize.height.coerceAtMost(screen.height).coerceAtLeast(1)
        frame.setSize(width, height)
        val x = screen.x + ((screen.width - width) / 2).coerceAtLeast(0)
        val y = screen.y + ((screen.height - height) / 2).coerceAtLeast(0)
        frame.setLocation(x, y)
    }

    fun visibleScreenBounds(
        screen: Rectangle = GraphicsEnvironment.getLocalGraphicsEnvironment().maximumWindowBounds,
        insets: Insets = Insets(0, 0, 0, 0),
    ): Rectangle {
        val x = screen.x + insets.left
        val y = screen.y + insets.top
        val width = (screen.width - insets.left - insets.right).coerceAtLeast(1)
        val height = (screen.height - insets.top - insets.bottom).coerceAtLeast(1)
        return Rectangle(x, y, width, height)
    }
}
