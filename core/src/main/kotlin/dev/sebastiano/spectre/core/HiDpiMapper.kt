package dev.sebastiano.spectre.core

import java.awt.Point
import java.awt.Rectangle
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Converts a Compose X coordinate to an AWT screen X coordinate.
 *
 * Compose semantics geometry is in physical (Compose) pixels. AWT/Robot operates in scaled screen
 * units. The conversion divides by scale, then adds the panel's screen offset.
 *
 * Formula from IntelliJ remote-driver's ComposeXpathDataModelExtension.
 *
 * Validated on Windows 11 + JBR 21 across 100/125/150/200% per-monitor DPI scaling, including the
 * mixed-DPI multi-monitor case where the secondary display sits at negative virtual-desktop
 * coordinates (#21). `GraphicsConfiguration.defaultTransform.scaleX` returns the actual Windows
 * percentage (1.0/1.25/1.5/2.0) and `LocalDensity.current` tracks it synchronously when the window
 * crosses a DPI boundary, so the same formula is correct on Windows with no platform branch —
 * `panelScreenX` simply carries any negative offset through. Empirical scenarios are pinned in
 * `WindowsHiDpiScenariosTest`.
 */
fun composeToAwtX(composeX: Float, scaleX: Float, panelScreenX: Int): Int =
    (composeX / scaleX).toInt() + panelScreenX

fun composeToAwtY(composeY: Float, scaleY: Float, panelScreenY: Int): Int =
    (composeY / scaleY).toInt() + panelScreenY

fun composeBoundsToAwtCenter(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    scaleX: Float,
    scaleY: Float,
    panelScreenX: Int,
    panelScreenY: Int,
): Point {
    val awtLeft = composeToAwtX(left, scaleX, panelScreenX)
    val awtTop = composeToAwtY(top, scaleY, panelScreenY)
    val awtRight = composeToAwtX(right, scaleX, panelScreenX)
    val awtBottom = composeToAwtY(bottom, scaleY, panelScreenY)
    return Point((awtLeft + awtRight) / 2, (awtTop + awtBottom) / 2)
}

fun composeBoundsToAwtRectangle(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    scaleX: Float,
    scaleY: Float,
    panelScreenX: Int,
    panelScreenY: Int,
): Rectangle {
    val awtLeft = floor((left / scaleX).toDouble()).toInt() + panelScreenX
    val awtTop = floor((top / scaleY).toDouble()).toInt() + panelScreenY
    val awtRight = ceil((right / scaleX).toDouble()).toInt() + panelScreenX
    val awtBottom = ceil((bottom / scaleY).toDouble()).toInt() + panelScreenY
    return Rectangle(
        awtLeft,
        awtTop,
        (awtRight - awtLeft).coerceAtLeast(MIN_CAPTURE_SIZE_PX),
        (awtBottom - awtTop).coerceAtLeast(MIN_CAPTURE_SIZE_PX),
    )
}

private const val MIN_CAPTURE_SIZE_PX = 1
