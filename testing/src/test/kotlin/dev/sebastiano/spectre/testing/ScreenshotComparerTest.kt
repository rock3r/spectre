package dev.sebastiano.spectre.testing

import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

/**
 * Synthetic-image unit tests for gold PNG comparison (#388). No live UI; pixels are constructed in
 * memory so boundary deltas and size mismatches are deterministic.
 */
class ScreenshotComparerTest {

    @Test
    fun `identical images match under strict defaults`() {
        val image = solid(width = 2, height = 2, rgb = 0x112233)
        val result = ScreenshotComparer.compare(expected = image, actual = copyOf(image))
        assertTrue(result.matches)
        assertEquals(0, result.differingPixels)
        assertFalse(result.sizeMismatch)
    }

    @Test
    fun `size mismatch fails even when every overlapping pixel is identical`() {
        val expected = solid(width = 2, height = 2, rgb = 0x000000)
        val actual = solid(width = 3, height = 2, rgb = 0x000000)
        val result = ScreenshotComparer.compare(expected, actual)
        assertFalse(result.matches)
        assertTrue(result.sizeMismatch)
        assertEquals(2, result.expectedWidth)
        assertEquals(2, result.expectedHeight)
        assertEquals(3, result.actualWidth)
        assertEquals(2, result.actualHeight)
    }

    @Test
    fun `a single-channel delta of 1 fails under maxChannelDelta 0`() {
        val expected = solid(width = 1, height = 1, rgb = 0x000000)
        val actual = solid(width = 1, height = 1, rgb = 0x000001)
        val result =
            ScreenshotComparer.compare(expected, actual, ScreenshotTolerance(maxChannelDelta = 0))
        assertFalse(result.matches)
        assertEquals(1, result.differingPixels)
    }

    @Test
    fun `a single-channel delta of 1 matches when maxChannelDelta is 1`() {
        val expected = solid(width = 1, height = 1, rgb = 0x000000)
        val actual = solid(width = 1, height = 1, rgb = 0x000001)
        val result =
            ScreenshotComparer.compare(expected, actual, ScreenshotTolerance(maxChannelDelta = 1))
        assertTrue(result.matches)
        assertEquals(0, result.differingPixels)
    }

    @Test
    fun `a single-channel delta of 2 fails when maxChannelDelta is 1`() {
        val expected = solid(width = 1, height = 1, rgb = 0x000000)
        val actual = solid(width = 1, height = 1, rgb = 0x000002)
        val result =
            ScreenshotComparer.compare(expected, actual, ScreenshotTolerance(maxChannelDelta = 1))
        assertFalse(result.matches)
        assertEquals(1, result.differingPixels)
    }

    @Test
    fun `maxChannelDelta above 255 is rejected`() {
        val error =
            assertFailsWith<IllegalArgumentException> { ScreenshotTolerance(maxChannelDelta = 256) }
        assertTrue(error.message!!.contains("256"), error.message)
    }

    @Test
    fun `maxChannelDelta of 255 is accepted`() {
        val expected = solid(width = 1, height = 1, rgb = 0x000000)
        val actual = solid(width = 1, height = 1, rgb = 0xFFFFFF)
        val result =
            ScreenshotComparer.compare(
                expected,
                actual,
                ScreenshotTolerance(maxChannelDelta = 255),
            )
        assertTrue(result.matches)
        assertEquals(0, result.differingPixels)
    }

    @Test
    fun `one differing pixel is allowed when maxDifferingPixels is 1`() {
        val expected = solid(width = 2, height = 2, rgb = 0xFFFFFF)
        val actual = copyOf(expected)
        actual.setRGB(0, 0, opaque(0x000000))
        val result =
            ScreenshotComparer.compare(
                expected,
                actual,
                ScreenshotTolerance(maxDifferingPixels = 1),
            )
        assertTrue(result.matches)
        assertEquals(1, result.differingPixels)
    }

    @Test
    fun `two differing pixels fail when maxDifferingPixels is 1`() {
        val expected = solid(width = 2, height = 2, rgb = 0xFFFFFF)
        val actual = copyOf(expected)
        actual.setRGB(0, 0, opaque(0x000000))
        actual.setRGB(1, 0, opaque(0x000000))
        val result =
            ScreenshotComparer.compare(
                expected,
                actual,
                ScreenshotTolerance(maxDifferingPixels = 1),
            )
        assertFalse(result.matches)
        assertEquals(2, result.differingPixels)
    }

    @Test
    fun `differing-pixel fraction boundary is inclusive`() {
        val expected = solid(width = 2, height = 2, rgb = 0xFFFFFF)
        val actual = copyOf(expected)
        actual.setRGB(0, 0, opaque(0x000000))
        val atBudget =
            ScreenshotComparer.compare(
                expected,
                actual,
                ScreenshotTolerance(maxDifferingPixelFraction = 0.25),
            )
        assertTrue(atBudget.matches)
        val underBudget =
            ScreenshotComparer.compare(
                expected,
                actual,
                ScreenshotTolerance(maxDifferingPixelFraction = 0.24),
            )
        assertFalse(underBudget.matches)
    }

    @Test
    fun `diff image marks only mismatched pixels`() {
        val expected = solid(width = 2, height = 2, rgb = 0x101010)
        val actual = copyOf(expected)
        actual.setRGB(1, 1, opaque(0xEE0000))
        val result = ScreenshotComparer.compare(expected, actual)
        assertFalse(result.matches)
        val diff = assertNotNull(result.diff)
        assertEquals(2, diff.width)
        assertEquals(2, diff.height)
        val matchRgb = diff.getRGB(0, 0) and 0x00FFFFFF
        val mismatchRgb = diff.getRGB(1, 1) and 0x00FFFFFF
        assertEquals(0x000000, matchRgb)
        assertTrue(mismatchRgb != 0x000000, "mismatch pixel must be highlighted, was $mismatchRgb")
    }

    private fun solid(width: Int, height: Int, rgb: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val packed = opaque(rgb)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, packed)
            }
        }
        return image
    }

    private fun copyOf(source: BufferedImage): BufferedImage {
        val copy = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = copy.createGraphics()
        graphics.drawImage(source, 0, 0, null)
        graphics.dispose()
        return copy
    }

    private fun opaque(rgb: Int): Int = (0xFF shl 24) or (rgb and 0x00FFFFFF)
}
