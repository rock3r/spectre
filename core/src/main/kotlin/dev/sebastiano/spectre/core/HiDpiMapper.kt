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
