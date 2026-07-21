@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
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
        val composeHandle = composeWindowHandleOrZero(window)
        val hostHandle = NativeWindowHandle.resolve(window)
        val (nativeHandle, cropRequired) =
            when {
                composeHandle != 0L ->
                    composeHandle to !surfaceFillsWindow(windowBounds, surfaceBounds)
                hostHandle != null ->
                    hostHandle to
                        (tracked.composePanel != null ||
                            !surfaceFillsWindow(windowBounds, surfaceBounds))
                else -> null to true
            }
        WindowIdentitySnapshot(
            index = index,
            surfaceId = tracked.surfaceId,
            title = (window as? Frame)?.title,
            isPopup = tracked.isPopup,
            nativeHandle = nativeHandle,
            cropRequired = cropRequired,
            windowBoundsOnScreen = windowBounds,
            surfaceBoundsOnScreen = Rectangle(surfaceBounds),
            surfaceBoundsInWindow = surfaceInWindow,
            scaleX = transform.scaleX,
            scaleY = transform.scaleY,
        )
    }

    private fun windowBoundsOnScreen(window: Window): Rectangle {
        val location = window.locationOnScreen
        val size = window.size
        return Rectangle(location.x, location.y, size.width, size.height)
    }

    private fun surfaceFillsWindow(windowBounds: Rectangle, surfaceBounds: Rectangle): Boolean {
        // Title bars / insets mean surface rarely equals the full window; treat "fills content"
        // as surface covering at least 90% of window area and sharing the same origin within a
        // small inset tolerance on the top (title bar).
        if (windowBounds.width <= 0 || windowBounds.height <= 0) return false
        val areaRatio =
            (surfaceBounds.width.toLong() * surfaceBounds.height) /
                (windowBounds.width.toLong() * windowBounds.height).toDouble()
        if (areaRatio < SURFACE_FILLS_WINDOW_AREA_RATIO) return false
        val leftOk = abs(surfaceBounds.x - windowBounds.x) <= INSET_TOLERANCE_PX
        val topOk = (surfaceBounds.y - windowBounds.y) in 0..MAX_TITLE_BAR_PX
        val rightOk =
            abs((surfaceBounds.x + surfaceBounds.width) - (windowBounds.x + windowBounds.width)) <=
                INSET_TOLERANCE_PX
        val bottomOk =
            abs(
                (surfaceBounds.y + surfaceBounds.height) - (windowBounds.y + windowBounds.height)
            ) <= INSET_TOLERANCE_PX
        return leftOk && topOk && rightOk && bottomOk
    }

    private fun composeWindowHandleOrZero(window: Window): Long =
        runCatching {
                val method =
                    window.javaClass.methods.firstOrNull {
                        it.name == "getWindowHandle" && it.parameterCount == 0
                    } ?: return 0L
                (method.invoke(window) as Number).toLong()
            }
            .getOrDefault(0L)

    private const val INSET_TOLERANCE_PX: Int = 4
    private const val MAX_TITLE_BAR_PX: Int = 80
    private const val SURFACE_FILLS_WINDOW_AREA_RATIO: Double = 0.9
}
