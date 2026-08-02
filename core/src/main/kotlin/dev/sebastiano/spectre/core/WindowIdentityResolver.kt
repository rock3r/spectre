@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.Window
import kotlin.math.abs

/** Builds [WindowIdentitySnapshot] values from [TrackedWindow] state on the EDT. */
@InternalSpectreApi
public object WindowIdentityResolver {

    public fun resolve(index: Int, tracked: TrackedWindow): WindowIdentitySnapshot = readOnEdt {
        val window = tracked.window
        val windowBounds = windowBoundsOnScreen(window)
        val surfaceBounds = tracked.composeSurfaceBoundsOnScreen
        val surfaceInWindow =
            Rectangle(
                surfaceBounds.x - windowBounds.x,
                surfaceBounds.y - windowBounds.y,
                surfaceBounds.width,
                surfaceBounds.height,
            )
        val transform =
            window.graphicsConfiguration?.defaultTransform ?: java.awt.geom.AffineTransform()
        val nativeHandle = NativeWindowHandle.resolve(window)
        // Native window handles always target the top-level OS window. Decorated Compose windows
        // expose a content-pane surface below the title bar, so crop is required whenever surface
        // and window rects differ — not only for embedded ComposePanel (spike #5).
        val cropRequired =
            nativeHandle == null || !rectsEqualWithin(windowBounds, surfaceBounds, PIXEL_TOLERANCE)
        WindowIdentitySnapshot(
            index = index,
            surfaceId = tracked.surfaceId,
            title = tracked.windowTitle,
            isPopup = tracked.isPopup,
            nativeHandle = nativeHandle,
            cropRequired = cropRequired,
            windowBoundsOnScreen = windowBounds,
            surfaceBoundsOnScreen = Rectangle(surfaceBounds),
            surfaceBoundsInWindow = surfaceInWindow,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
            translateX = transform.translateX,
            translateY = transform.translateY,
        )
    }

    /**
     * Top-level window bounds in screen coordinates.
     *
     * When the window is displayable but not yet showing (delayed-show hosts admitted by
     * [WindowTracker] for #362), `locationOnScreen` throws. Fall back to the window's layout
     * position/size so identity resolution does not fail the whole list.
     */
    private fun windowBoundsOnScreen(window: Window): Rectangle {
        return try {
            val location = window.locationOnScreen
            val size = window.size
            Rectangle(location.x, location.y, size.width, size.height)
        } catch (_: java.awt.IllegalComponentStateException) {
            Rectangle(
                window.x,
                window.y,
                window.width.coerceAtLeast(0),
                window.height.coerceAtLeast(0),
            )
        }
    }

    private fun rectsEqualWithin(a: Rectangle, b: Rectangle, tolerancePx: Int): Boolean =
        abs(a.x - b.x) <= tolerancePx &&
            abs(a.y - b.y) <= tolerancePx &&
            abs(a.width - b.width) <= tolerancePx &&
            abs(a.height - b.height) <= tolerancePx

    /** Rounding / sub-pixel placement only — not title-bar height. */
    private const val PIXEL_TOLERANCE: Int = 1
}
