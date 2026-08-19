@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Window stills must keep the pixel scale the native helper produced.
 *
 * The macOS/Windows/Linux still helpers hand back backing-store pixels (a 1600x1000dp window on a
 * 2x display arrives as 3200x2000) while [WindowCapture.boundsOnScreen] stays in AWT logical units.
 * Resampling those pixels down to the logical rectangle made every still 1x while `Recorder` kept
 * writing screen-pixel video for the same window — the asymmetry these tests pin shut.
 */
class WindowStillScaleTest {

    @Test
    fun `an uncropped window still is handed back untouched`() {
        val image = BufferedImage(3200, 2000, BufferedImage.TYPE_INT_ARGB)
        val bounds = Rectangle(0, 0, 1600, 1000)

        val still = windowStillForRegion(WindowCapture(image, bounds), bounds)

        assertSame(image, still.image, "a still that needs no crop must not be resampled")
        assertEquals(bounds, still.boundsOnScreen)
    }

    @Test
    fun `cropping to the Compose surface keeps device pixels`() {
        // 1600x1000dp window on a 2x display, 28dp of title-bar chrome above the Compose surface.
        val image = BufferedImage(3200, 2000, BufferedImage.TYPE_INT_ARGB)
        val windowBounds = Rectangle(0, 0, 1600, 1000)
        val composeSurface = Rectangle(0, 28, 1600, 972)

        val still = windowStillForRegion(WindowCapture(image, windowBounds), composeSurface)

        assertEquals(3200, still.image.width, "cropped still must stay at 2x width")
        assertEquals(1944, still.image.height, "cropped still must stay at 2x height")
        assertEquals(composeSurface, still.boundsOnScreen, "bounds stay in logical screen units")
    }

    @Test
    fun `cropped pixels are read through the image-space transform`() {
        val image = BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB)
        // Logical (10,20) is device (20,40) at 2x — the first pixel of the crop below.
        image.setRGB(20, 40, MARKER)
        val windowBounds = Rectangle(0, 0, 100, 100)

        val still =
            windowStillForRegion(WindowCapture(image, windowBounds), Rectangle(10, 20, 50, 50))

        assertEquals(100, still.image.width)
        assertEquals(100, still.image.height)
        assertEquals(MARKER, still.image.getRGB(0, 0), "crop must start at the scaled origin")
    }

    @Test
    fun `a still clipped by the screen keeps the visible region only`() {
        val image = BufferedImage(400, 400, BufferedImage.TYPE_INT_ARGB)
        val windowBounds = Rectangle(0, 0, 200, 200)

        val still =
            windowStillForRegion(WindowCapture(image, windowBounds), Rectangle(100, 100, 200, 200))

        assertEquals(Rectangle(100, 100, 100, 100), still.boundsOnScreen)
        assertEquals(200, still.image.width, "clipped still keeps the 2x scale")
        assertEquals(200, still.image.height)
    }

    private companion object {
        const val MARKER: Int = 0xFF112233.toInt()
    }
}
