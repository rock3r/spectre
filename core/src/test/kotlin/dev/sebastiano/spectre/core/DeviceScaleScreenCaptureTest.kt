@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Rectangle
import java.awt.image.BaseMultiResolutionImage
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Region stills written as artifacts must carry screen pixels, not the logical rectangle
 * `java.awt.Robot.createScreenCapture` downsamples to. The device-pixel variants are only reachable
 * through `createMultiResolutionScreenCapture`, so these tests pin the variant-selection rule.
 */
class DeviceScaleScreenCaptureTest {

    @Test
    fun `picks the densest resolution variant`() {
        val logical = argb(100, 100)
        val device = argb(200, 200)

        val picked =
            highestResolutionVariant(
                BaseMultiResolutionImage(logical, device),
                Rectangle(0, 0, 100, 100),
            )

        assertEquals(200, picked.width)
        assertEquals(200, picked.height)
    }

    @Test
    fun `hands back the only variant on a 1x display without resampling`() {
        val logical = argb(100, 100)

        val picked =
            highestResolutionVariant(BaseMultiResolutionImage(logical), Rectangle(0, 0, 100, 100))

        assertSame(logical, picked, "a single-variant capture must not be copied")
    }

    @Test
    fun `variant order does not decide the winner`() {
        val device = argb(300, 200)
        val logical = argb(150, 100)

        val picked =
            highestResolutionVariant(
                BaseMultiResolutionImage(1, device, logical),
                Rectangle(0, 0, 150, 100),
            )

        assertEquals(300, picked.width)
        assertEquals(200, picked.height)
    }

    @Test
    fun `still region capture uses the device-scale source, not the logical one`() {
        var logicalCalls = 0
        var deviceCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    logicalCalls += 1
                    argb(100, 100)
                },
                nativeCapture = { error("region stills must not touch the native window path") },
                deviceScaleRegionCapture = {
                    deviceCalls += 1
                    argb(200, 200)
                },
            )

        val still = backend.captureStillRegion(Rectangle(0, 0, 100, 100))

        assertEquals(0, logicalCalls, "still artifacts must not use the downsampling path")
        assertEquals(1, deviceCalls)
        assertEquals(200, still.width)
        assertEquals(200, still.height)
    }

    @Test
    fun `frame-hash region capture stays on the logical path`() {
        var logicalCalls = 0
        var deviceCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    logicalCalls += 1
                    argb(100, 100)
                },
                nativeCapture = { error("region capture must not touch the native window path") },
                deviceScaleRegionCapture = {
                    deviceCalls += 1
                    argb(200, 200)
                },
            )

        val image = backend.captureRegion(Rectangle(0, 0, 100, 100))

        assertEquals(1, logicalCalls)
        assertEquals(0, deviceCalls, "visual-idle hashing must keep its cheaper logical frames")
        assertTrue(image.width == 100 && image.height == 100)
    }

    private fun argb(width: Int, height: Int): BufferedImage =
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
}
