@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Routing contract for #355 atomic [ComposeAutomator.capture]: when the native window still path is
 * used, region capture must not run. Production `capture()` calls the same crop-of-window path as
 * [screenshotTrackedRegionCapture] tested here via the public backend seam.
 */
class AtomicCaptureWindowRoutingTest {

    @Test
    fun `atomic still prefers window capture and never calls region when native succeeds`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("atomic-capture-window").apply {
                setBounds(10, 20, 160, 120)
                addNotify()
            }
        val windowBounds = Rectangle(frame.bounds)
        val surface = Rectangle(10, 44, 160, 96)
        val nativeImage = solidImage(160, 120, 0xFFAABBCC.toInt())
        var regionCalls = 0
        var windowCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    error("atomic capture must not use screen-region when native works")
                },
                nativeCapture = {
                    windowCalls += 1
                    nativeImage
                },
                nativeCaptureBounds = { _, _, bounds, _ -> bounds },
            )
        try {
            val capture =
                backend.captureWindow(
                    TrackedWindow("t", frame, composePanel = null, isPopup = false),
                    windowBounds,
                    Insets(24, 0, 0, 0),
                )
            // Same call production makes to crop the native frame to the Compose surface.
            val cropped = windowStillForRegion(capture, surface)
            assertEquals(1, windowCalls)
            assertEquals(0, regionCalls)
            assertEquals(160, cropped.image.width)
            assertEquals(96, cropped.image.height)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `atomic still fails loudly when native window capture is disabled`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("atomic-disabled").apply {
                setBounds(0, 0, 80, 60)
                addNotify()
            }
        var regionCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    solidImage(1, 1, 0)
                },
                nativeCapture = { solidImage(80, 60, 0xFF00FF00.toInt()) },
                nativeCaptureEnabled = { false },
            )
        try {
            assertFailsWith<UnsupportedOperationException> {
                backend.captureWindow(
                    TrackedWindow("t", frame, composePanel = null, isPopup = false),
                    Rectangle(frame.bounds),
                    Insets(0, 0, 0, 0),
                )
            }
            assertEquals(0, regionCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `atomic still fails loudly when native bridge is unavailable`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("atomic-no-bridge").apply {
                setBounds(0, 0, 40, 30)
                addNotify()
            }
        var regionCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    solidImage(1, 1, 0)
                },
                nativeCapture = {
                    throw UnsupportedOperationException(
                        "Native window capture bridge is unavailable"
                    )
                },
            )
        try {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    backend.captureWindow(
                        TrackedWindow("t", frame, composePanel = null, isPopup = false),
                        Rectangle(frame.bounds),
                        Insets(0, 0, 0, 0),
                    )
                }
            assertTrue(error.message!!.contains("unavailable", ignoreCase = true))
            assertEquals(0, regionCalls)
        } finally {
            frame.dispose()
        }
    }

    private fun solidImage(width: Int, height: Int, rgb: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until height) {
            for (x in 0 until width) {
                image.setRGB(x, y, rgb)
            }
        }
        return image
    }
}
