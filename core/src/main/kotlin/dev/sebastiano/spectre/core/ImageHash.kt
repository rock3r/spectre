package dev.sebastiano.spectre.core

import java.awt.image.BufferedImage

/** Hashes only pixels in [image]'s logical bounds, including when it is a cropped subimage. */
internal fun imageHash(image: BufferedImage): Int {
    val width = image.width
    val pixels = image.getRGB(0, 0, width, image.height, null, 0, width)
    return pixels.contentHashCode()
}
