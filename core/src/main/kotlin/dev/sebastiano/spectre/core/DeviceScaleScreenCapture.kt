package dev.sebastiano.spectre.core

import java.awt.Image
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage

/**
 * Picks the densest variant of a multi-resolution screen capture.
 *
 * Taking the largest variant is what makes a still match the recorder on a Retina display, and it
 * degrades safely on a 1x display, where there is a single variant.
 *
 * **Mixed-density desktops are bounded by the JDK, not by this function.**
 * `Robot.createMultiResolutionScreenCapture` does not capture per display: `Robot`'s
 * `createCompatibleImage` resolves one `GraphicsConfiguration` from the *centre* of the requested
 * rectangle and offers variants for that display's scale alone. A region spanning monitors of
 * different densities is therefore captured at the centre display's scale — so a rectangle centred
 * on a 1x monitor yields no 2x variant at all, and the Retina portion stays downsampled. Capturing
 * each intersecting display at its own scale and composing them would lift that limit; it is not
 * something variant selection can fix. Window-scoped stills are unaffected: they go through the
 * native helpers, which use the target window's own screen scale.
 *
 * [region] is the requested logical rectangle, used only to ask for a base variant when a backend
 * reports no variants at all.
 */
internal fun highestResolutionVariant(
    capture: MultiResolutionImage,
    region: Rectangle,
): BufferedImage {
    val densest =
        capture.resolutionVariants
            .filter { it.getWidth(null) > 0 && it.getHeight(null) > 0 }
            .maxByOrNull { it.getWidth(null).toLong() * it.getHeight(null).toLong() }
            ?: capture.getResolutionVariant(
                region.width.toDouble().coerceAtLeast(1.0),
                region.height.toDouble().coerceAtLeast(1.0),
            )
    return densest.asBufferedImage()
}

/** Robot hands back `BufferedImage` variants; only a foreign backend would need the copy. */
private fun Image.asBufferedImage(): BufferedImage =
    this as? BufferedImage
        ?: BufferedImage(
                getWidth(null).coerceAtLeast(1),
                getHeight(null).coerceAtLeast(1),
                BufferedImage.TYPE_INT_ARGB,
            )
            .also { copy ->
                val graphics = copy.createGraphics()
                try {
                    graphics.drawImage(this, 0, 0, null)
                } finally {
                    graphics.dispose()
                }
            }
