package dev.sebastiano.spectre.core

import androidx.compose.ui.awt.ComposePanel
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JFrame

data class TrackedWindow(
    val surfaceId: String,
    val window: Window,
    val composePanel: ComposePanel?,
    val isPopup: Boolean,
) {

    /**
     * The screen location of the Compose content origin.
     *
     * For ComposeWindow (a JFrame), the Compose content renders inside the content pane, which sits
     * below the title bar. For embedded ComposePanels, the panel's own location is used. This is
     * critical for correct coordinate mapping: boundsInWindow is relative to the content area, not
     * the window frame.
     */
    val composeContentOrigin: Point
        get() =
            composePanel?.locationOnScreen
                ?: (window as? JFrame)?.contentPane?.locationOnScreen
                ?: window.locationOnScreen

    val composeSurfaceBoundsOnScreen: Rectangle
        get() =
            composePanel?.let { Rectangle(it.locationOnScreen, it.size) }
                ?: (window as? JFrame)?.contentPane?.let { Rectangle(it.locationOnScreen, it.size) }
                ?: Rectangle(window.locationOnScreen, window.size)
}
