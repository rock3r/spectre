package dev.sebastiano.spectre.core.capture

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageSpaceBoundsTest {

    @Test
    fun `identity scale maps screen offsets relative to capture origin`() {
        val image =
            screenRectToImageRect(
                screen = Rectangle(/* x= */ 110, /* y= */ 220, /* w= */ 40, /* h= */ 20),
                captureOriginX = 100,
                captureOriginY = 200,
                captureAwtWidth = 200,
                captureAwtHeight = 100,
                imageWidth = 200,
                imageHeight = 100,
            )
        assertEquals(CaptureRect(x = 10, y = 20, width = 40, height = 20), image)
    }

    @Test
    fun `HiDPI 2x maps AWT screen units into image pixels`() {
        // Capture region is 100×50 AWT units but the PNG is 200×100 physical pixels.
        val image =
            screenRectToImageRect(
                screen = Rectangle(/* x= */ 110, /* y= */ 225, /* w= */ 20, /* h= */ 10),
                captureOriginX = 100,
                captureOriginY = 200,
                captureAwtWidth = 100,
                captureAwtHeight = 50,
                imageWidth = 200,
                imageHeight = 100,
            )
        assertEquals(CaptureRect(x = 20, y = 50, width = 40, height = 20), image)
    }

    @Test
    fun `zero awt size does not throw and yields zeroed rect`() {
        val image =
            screenRectToImageRect(
                screen = Rectangle(10, 10, 5, 5),
                captureOriginX = 0,
                captureOriginY = 0,
                captureAwtWidth = 0,
                captureAwtHeight = 0,
                imageWidth = 10,
                imageHeight = 10,
            )
        assertEquals(CaptureRect(x = 0, y = 0, width = 0, height = 0), image)
    }

    @Test
    fun `fractional scale derives size from transformed edges`() {
        // 150% density: 1 AWT unit → 1.5 image pixels. Independent rounding of origin and size
        // would map offset=1,width=1 to x=2,width=2 (right edge 4) instead of right edge 3.
        val image =
            screenRectToImageRect(
                screen = Rectangle(/* x= */ 101, /* y= */ 200, /* w= */ 1, /* h= */ 1),
                captureOriginX = 100,
                captureOriginY = 200,
                captureAwtWidth = 100,
                captureAwtHeight = 100,
                imageWidth = 150,
                imageHeight = 150,
            )
        assertEquals(CaptureRect(x = 2, y = 0, width = 1, height = 2), image)
    }
}
