package dev.sebastiano.spectre.sample

import androidx.compose.ui.geometry.Rect
import java.awt.IllegalComponentStateException
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities

internal const val UNFOCUSED_WINDOW_GAP = 16

/**
 * Maps a Compose `boundsInWindow` rect onto screen pixels given the hosting panel's origin and the
 * graphics-configuration scale. The CI unfocused-click miss at (270, 96) is this formula with a
 * panel origin of (0, 0): the local counter center, not the SUT's requested `setLocation`.
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

/**
 * Xvfb without a window manager often maps both always-on-top frames at the origin, so the
 * distractor covers the SUT counter. Slide the distractor just past the SUT's right edge when the
 * requested (or observed) bounds intersect.
 */
internal fun separateOverlappingBounds(sut: Rectangle, distractor: Rectangle, gap: Int): Rectangle {
    if (!sut.intersects(distractor)) {
        return Rectangle(distractor)
    }
    return Rectangle(sut.x + sut.width + gap, distractor.y, distractor.width, distractor.height)
}

/**
 * Pick a clickable point inside [target] that is not covered by [obstacle]. Used when the
 * distractor still overlaps the counter after a `setLocation` that Xvfb ignored.
 */
internal fun unobscuredPointIn(target: Rectangle, obstacle: Rectangle): Point {
    val center = Point(target.centerX.toInt(), target.centerY.toInt())
    if (obstacle.isEmpty || !obstacle.contains(center)) {
        return center
    }
    val rightX = obstacle.x + obstacle.width
    if (rightX < target.x + target.width) {
        val point = Point((rightX + target.x + target.width) / 2, target.centerY.toInt())
        if (target.contains(point) && !obstacle.contains(point)) {
            return point
        }
    }
    val belowY = obstacle.y + obstacle.height
    if (belowY < target.y + target.height) {
        val point = Point(target.centerX.toInt(), (belowY + target.y + target.height) / 2)
        if (target.contains(point) && !obstacle.contains(point)) {
            return point
        }
    }
    val leftSpan = obstacle.x - target.x
    if (leftSpan > 1) {
        val point = Point(target.x + leftSpan / 2, target.centerY.toInt())
        if (target.contains(point) && !obstacle.contains(point)) {
            return point
        }
    }
    return center
}

internal fun composeTargetOnScreen(state: SmokeState, rect: Rect): Rectangle? {
    if (rect.width <= 0f || rect.height <= 0f) return null
    val frame = state.frame ?: return null
    val panel = state.composePanel ?: return null
    return invokeOnEdt {
        val gc = (frame as Window).graphicsConfiguration ?: return@invokeOnEdt null
        val xform = gc.defaultTransform
        val panelLoc =
            try {
                panel.locationOnScreen
            } catch (_: IllegalComponentStateException) {
                return@invokeOnEdt null
            }
        composeRectOnScreen(
            rect,
            panelScreen = panelLoc,
            scaleX = xform.scaleX.toFloat(),
            scaleY = xform.scaleY.toFloat(),
        )
    }
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

internal fun pinUnfocusedWindowsApart(state: SmokeState, distractor: JFrame) {
    invokeOnEdt {
        val sut = state.frame ?: return@invokeOnEdt
        val sutBounds = frameScreenBounds(sut)
        val distractorBounds = frameScreenBounds(distractor)
        val separated =
            separateOverlappingBounds(sutBounds, distractorBounds, gap = UNFOCUSED_WINDOW_GAP)
        if (separated.x != distractorBounds.x || separated.y != distractorBounds.y) {
            println(
                "unfocused smoke: overlapping frames sut=$sutBounds distractor=$distractorBounds; " +
                    "moving distractor to (${separated.x},${separated.y})"
            )
            distractor.setLocation(separated.x, separated.y)
        }
        // Keep the distractor as the focus owner after any move so the starting-state
        // scenario still sees SUT unfocused.
        distractor.toFront()
        distractor.requestFocus()
    }
}

internal fun unfocusedCounterClick(state: SmokeState, distractor: JFrame): Point? {
    val fallback = awtCenter(state, state.counterBounds) ?: return null
    val target = composeTargetOnScreen(state, state.counterBounds) ?: return fallback
    return invokeOnEdt {
        val obstacle = frameScreenBounds(distractor)
        unobscuredPointIn(target, obstacle)
    }
}

internal fun <T> invokeOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return block()
    }
    val result = AtomicReference<T>()
    SwingUtilities.invokeAndWait { result.set(block()) }
    return result.get()
}
