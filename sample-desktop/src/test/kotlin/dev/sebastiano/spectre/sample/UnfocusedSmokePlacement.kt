package dev.sebastiano.spectre.sample

import androidx.compose.ui.geometry.Rect
import java.awt.IllegalComponentStateException
import java.awt.Point
import java.awt.Rectangle
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities

/**
 * Maps a Compose `boundsInWindow` rect onto screen pixels given the hosting panel's origin and the
 * graphics-configuration scale. The CI unfocused-click miss at (270, 96) is this formula with a
 * panel origin of (0, 0): the local counter center, because AWT reported the SUT at (0,0) while its
 * X window sat at the requested spot (#583). The Linux smokes now fix that with
 * [reapplyFrameLocation].
 */
internal fun composeCenterOnScreen(
    rect: Rect,
    panelScreen: Point,
    scaleX: Float,
    scaleY: Float,
): Point {
    val bounds = composeRectOnScreen(rect, panelScreen, scaleX, scaleY)
    return Point(bounds.centerX.toInt(), bounds.centerY.toInt())
}

internal fun composeRectOnScreen(
    rect: Rect,
    panelScreen: Point,
    scaleX: Float,
    scaleY: Float,
): Rectangle {
    val left = (rect.left / scaleX).toInt() + panelScreen.x
    val top = (rect.top / scaleY).toInt() + panelScreen.y
    val right = (rect.right / scaleX).toInt() + panelScreen.x
    val bottom = (rect.bottom / scaleY).toInt() + panelScreen.y
    return Rectangle(left, top, (right - left).coerceAtLeast(0), (bottom - top).coerceAtLeast(0))
}

internal fun frameScreenBounds(frame: JFrame): Rectangle {
    val loc =
        try {
            frame.locationOnScreen
        } catch (_: IllegalComponentStateException) {
            frame.location
        }
    return Rectangle(loc, frame.size)
}

internal fun screenCenter(frame: JFrame): Point = invokeOnEdt {
    val bounds = frameScreenBounds(frame)
    Point(bounds.centerX.toInt(), bounds.centerY.toInt())
}

internal fun <T> invokeOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return block()
    }
    val result = AtomicReference<T>()
    SwingUtilities.invokeAndWait { result.set(block()) }
    return result.get()
}
