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
fun composeToAwtX(composeX: Float, scale: Float, panelScreenX: Int): Int =
    (composeX / scale).toInt() + panelScreenX

fun composeToAwtY(composeY: Float, scale: Float, panelScreenY: Int): Int =
    (composeY / scale).toInt() + panelScreenY

fun composeToAwtSize(composeWidth: Float, composeHeight: Float, scale: Float): Pair<Int, Int> =
    Pair((composeWidth / scale).toInt(), (composeHeight / scale).toInt())

fun composeBoundsToAwtCenter(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    scale: Float,
    panelScreenX: Int,
    panelScreenY: Int,
): Point {
    val awtLeft = composeToAwtX(left, scale, panelScreenX)
    val awtTop = composeToAwtY(top, scale, panelScreenY)
    val awtRight = composeToAwtX(right, scale, panelScreenX)
    val awtBottom = composeToAwtY(bottom, scale, panelScreenY)
    return Point((awtLeft + awtRight) / 2, (awtTop + awtBottom) / 2)
}
