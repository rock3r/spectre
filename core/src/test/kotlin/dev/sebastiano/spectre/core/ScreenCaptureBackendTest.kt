@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScreenCaptureBackendTest {

    @Test
    fun `tracked Frame capture prefers the native window backend`() {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(40, 50, 300, 200) }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        var fallbackCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    fallbackCalls += 1
                    error("should not fall back")
                },
                nativeCapture = { expected },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(frame)))
            assertEquals(0, fallbackCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `native unavailability falls back to the full window region`() {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(40, 50, 300, 200) }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        var requested: Rectangle? = null
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { region ->
                    requested = region
                    expected
                },
                nativeCapture = {
                    throw IllegalStateException("macOS window screenshot is unavailable")
                },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(frame)))
            assertEquals(Rectangle(40, 50, 300, 200), requested)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `unsupported native capture falls back to the full window region`() {
        assertFallbackFor(UnsupportedOperationException("no native backend"))
    }

    @Test
    fun `blank Windows title falls back to the full window region`() {
        assertFallbackFor(
            IllegalArgumentException(
                "AutoScreenshotter.captureWindow requires a non-blank window title on Windows."
            )
        )
    }

    @Test
    fun `missing platform helper falls back to the full window region`() {
        assertFallbackFor(IllegalStateException("Windows capture helper not found"))
    }

    @Test
    fun `unavailable Wayland frame geometry falls back to the full window region`() {
        assertTrue(
            shouldFallBackToRegionCapture(
                IllegalStateException(
                    "Could not determine WM frame extents for window 'fixture' on this Wayland session"
                )
            )
        )
    }

    @Test
    fun `disabled native capture delegates to the configured region backend`() {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(40, 50, 300, 200) }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { expected },
                nativeCapture = { error("native capture should be disabled") },
                nativeCaptureEnabled = { false },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(frame)))
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `duplicate window titles use the region fallback`() {
        assumeLiveAwtAvailable()
        val first = Frame("same title").apply { addNotify() }
        val second = Frame("same title").apply { addNotify() }
        val expected = BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB)
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { expected },
                nativeCapture = { error("native capture should not be used for duplicate titles") },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(second)))
        } finally {
            first.dispose()
            second.dispose()
        }
    }

    @Test
    fun `image hash excludes pixels outside a cropped subimage`() {
        val parent = BufferedImage(4, 2, BufferedImage.TYPE_INT_ARGB)
        val crop = parent.getSubimage(0, 0, 2, 2)
        val initialHash = imageHash(crop)

        parent.setRGB(3, 1, 0xFF00FF00.toInt())

        assertEquals(initialHash, imageHash(crop))
    }

    private fun assertFallbackFor(error: RuntimeException) {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(40, 50, 300, 200) }
        val expected = BufferedImage(300, 200, BufferedImage.TYPE_INT_ARGB)
        var requested: Rectangle? = null
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { region ->
                    requested = region
                    expected
                },
                nativeCapture = { throw error },
            )
        try {
            assertSame(expected, backend.captureWindow(tracked(frame)))
            assertEquals(Rectangle(40, 50, 300, 200), requested)
        } finally {
            frame.dispose()
        }
    }

    private fun tracked(frame: Frame): TrackedWindow =
        TrackedWindow("test", frame, composePanel = null, isPopup = false)
}
