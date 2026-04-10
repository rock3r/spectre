package dev.sebastiano.spectre.core

import java.awt.Point

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
