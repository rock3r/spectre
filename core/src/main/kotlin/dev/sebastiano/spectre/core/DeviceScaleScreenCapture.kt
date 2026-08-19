package dev.sebastiano.spectre.core

import java.awt.Image
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.awt.image.MultiResolutionImage

/**
 * Picks the densest variant of a multi-resolution screen capture.
 *
 * `Robot.createMultiResolutionScreenCapture` returns one variant per distinct display scale the
 * requested rectangle touches. Taking the largest is what makes a still match the recorder on a
 * Retina display, and it degrades correctly elsewhere: on a 1x display there is a single variant,
 * and across mixed densities the densest one upscales the lower-density displays rather than
 * dropping them. [region] is the requested logical rectangle, used only to ask for a base variant
 * when a backend reports no variants at all.
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
