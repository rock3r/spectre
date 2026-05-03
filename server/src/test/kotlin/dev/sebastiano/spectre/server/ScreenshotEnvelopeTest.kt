package dev.sebastiano.spectre.server

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit coverage for the `BufferedImage → ScreenshotResponse` envelope encoding. Previously this was
 * exercised end-to-end through the headless `RobotDriver`'s 1×1 placeholder, but the headless
 * driver now throws on `screenshot()`, so the wire-format coverage lives here instead.
 */
class ScreenshotEnvelopeTest {

    @Test
    fun `toScreenshotResponse encodes the image as a base64 PNG with matching dimensions`() {
        val image = BufferedImage(7, 11, BufferedImage.TYPE_INT_ARGB)

        val response = image.toScreenshotResponse()

        assertEquals(7, response.width)
        assertEquals(11, response.height)
        assertTrue(response.pngBase64.isNotEmpty(), "PNG payload should be present")

        // The decoded image must be a valid PNG that round-trips to the original size — proves
        // the envelope is well-formed bytes, not just non-empty base64.
        val decoded =
            ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(response.pngBase64)))
        assertEquals(7, decoded.width)
        assertEquals(11, decoded.height)
    }
}
