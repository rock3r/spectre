package dev.sebastiano.spectre.core.capture

import java.awt.Rectangle
import kotlin.math.roundToInt

/**
 * Maps a screen-space rectangle into the image-pixel space of a capture PNG.
 *
 * [captureOriginX]/[captureOriginY] and [captureAwtWidth]/[captureAwtHeight] describe the AWT
 * rectangle that was fed to the screenshot backend. [imageWidth]/[imageHeight] are the actual PNG
 * dimensions (often 2× the AWT size on HiDPI). The scale factors reconcile the two so node bounds
 * line up with the pixels an agent sees in the PNG.
 */
public fun screenRectToImageRect(
    screen: Rectangle,
    captureOriginX: Int,
    captureOriginY: Int,
    captureAwtWidth: Int,
    captureAwtHeight: Int,
    imageWidth: Int,
    imageHeight: Int,
): CaptureRect {
    if (captureAwtWidth <= 0 || captureAwtHeight <= 0 || imageWidth <= 0 || imageHeight <= 0) {
        return CaptureRect(x = 0, y = 0, width = 0, height = 0)
    }
    val scaleX = imageWidth.toDouble() / captureAwtWidth.toDouble()
    val scaleY = imageHeight.toDouble() / captureAwtHeight.toDouble()
    // Transform both edges, then derive size. Rounding origin and size independently can make
    // the image-space right/bottom disagree with the transformed screen-space edge on fractional
    // densities (for example 150% where PNG is 1.5× the AWT region).
    val left = ((screen.x - captureOriginX) * scaleX).roundToInt()
    val top = ((screen.y - captureOriginY) * scaleY).roundToInt()
    val right = ((screen.x - captureOriginX + screen.width) * scaleX).roundToInt()
    val bottom = ((screen.y - captureOriginY + screen.height) * scaleY).roundToInt()
    return CaptureRect(
        x = left,
        y = top,
        width = (right - left).coerceAtLeast(0),
        height = (bottom - top).coerceAtLeast(0),
    )
}
