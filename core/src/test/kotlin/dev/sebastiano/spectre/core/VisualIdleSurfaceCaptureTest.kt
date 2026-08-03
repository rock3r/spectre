@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Routing contract for #355 visual-idle sampling: when a native window backend is available,
 * surfaces are hashed from window-scoped pixels (not `captureRegion` / Robot screen rects).
 */
class VisualIdleSurfaceCaptureTest {

    @Test
    fun `prefers window-scoped capture and never calls region when native path succeeds`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("visual-idle-window").apply {
                setBounds(40, 50, 200, 100)
                addNotify()
            }
        val windowBounds = Rectangle(frame.bounds)
        val surface = Rectangle(40, 74, 200, 76)
        val nativeImage = solidImage(200, 100, 0xFF112233.toInt())
        var regionCalls = 0
        var windowCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    error(
                        "screen-region capture must not be used for visual-idle when native works"
                    )
                },
                nativeCapture = {
                    windowCalls += 1
                    nativeImage
                },
                nativeCaptureBounds = { _, _, bounds, _ -> bounds },
            )
        try {
            val image =
                captureSurfaceForVisualIdle(
                    backend = backend,
                    window = tracked(frame),
                    surfaceRegion = surface,
                    windowBounds = windowBounds,
                    frameInsets = Insets(24, 0, 0, 0),
                    nativeWindowCaptureAvailable = true,
                )
            assertEquals(1, windowCalls)
            assertEquals(0, regionCalls)
            assertEquals(200, image!!.width)
            // Cropped to surface height (100 - 24 chrome).
            assertEquals(76, image.height)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `uses region capture only when native window backend is unavailable`() {
        // Frame is only a TrackedWindow carrier; region path never calls native. Still needs a
        // non-headless AWT toolkit to construct the Frame on CI Ubuntu without xvfb.
        assumeLiveAwtAvailable()
        var regionCalls = 0
        var windowCalls = 0
        val regionImage = solidImage(10, 10, 0xFF00FF00.toInt())
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    regionImage
                },
                nativeCapture = {
                    windowCalls += 1
                    error("native must not be invoked when marked unavailable")
                },
            )
        val frame = Frame()
        try {
            val image =
                captureSurfaceForVisualIdle(
                    backend = backend,
                    window = tracked(frame),
                    surfaceRegion = Rectangle(1, 2, 10, 10),
                    windowBounds = Rectangle(0, 0, 100, 100),
                    frameInsets = Insets(0, 0, 0, 0),
                    nativeWindowCaptureAvailable = false,
                )
            assertSame(regionImage, image)
            assertEquals(1, regionCalls)
            assertEquals(0, windowCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `does not silently fall back to region when native is available but fails`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("ambiguous-a").apply {
                setBounds(0, 0, 80, 60)
                addNotify()
            }
        val twin =
            Frame("ambiguous-a").apply {
                setBounds(100, 0, 80, 60)
                addNotify()
            }
        var regionCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    solidImage(1, 1, 0)
                },
                nativeCapture = { solidImage(80, 60, 0xFFAABBCC.toInt()) },
                nativeCaptureEnabled = { true },
                nativeCaptureDisambiguatesTitles = { false },
            )
        try {
            val image =
                captureSurfaceForVisualIdle(
                    backend = backend,
                    window = tracked(frame),
                    surfaceRegion = Rectangle(frame.bounds),
                    windowBounds = Rectangle(frame.bounds),
                    frameInsets = Insets(0, 0, 0, 0),
                    nativeWindowCaptureAvailable = true,
                )
            assertNull(image, "ambiguous native identity must not yield region pixels")
            assertEquals(0, regionCalls)
        } finally {
            twin.dispose()
            frame.dispose()
        }
    }

    @Test
    fun `hashes combined surfaces from window captures in order`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("hash-order").apply {
                setBounds(0, 0, 40, 20)
                addNotify()
            }
        val imgA = solidImage(40, 20, 0xFF010101.toInt())
        val imgB = solidImage(40, 20, 0xFF020202.toInt())
        var n = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("no region") },
                nativeCapture = {
                    n += 1
                    if (n == 1) imgA else imgB
                },
                nativeCaptureBounds = { _, _, bounds, _ -> bounds },
            )
        try {
            val surfaces =
                listOf(
                    Triple(tracked(frame), Rectangle(0, 0, 40, 20), Rectangle(0, 0, 40, 20)),
                    Triple(tracked(frame), Rectangle(0, 0, 40, 20), Rectangle(0, 0, 40, 20)),
                )
            val hash =
                hashTrackedSurfacesForVisualIdle(
                    surfaces = surfaces.map { it.first to it.second },
                    windowBoundsFor = { surfaces.first().third },
                    frameInsetsFor = { Insets(0, 0, 0, 0) },
                    backend = backend,
                    nativeWindowCaptureAvailable = true,
                )
            val expected = intArrayOf(imageHash(imgA), imageHash(imgB)).contentHashCode()
            assertEquals(expected, hash)
            assertTrue(n >= 2)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `hash returns null when any surface cannot be sampled under native routing`() {
        assumeLiveAwtAvailable()
        val frame =
            Frame("fail-one").apply {
                setBounds(0, 0, 40, 20)
                addNotify()
            }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("no region") },
                nativeCapture = { error("native boom") },
            )
        try {
            val hash =
                hashTrackedSurfacesForVisualIdle(
                    surfaces = listOf(tracked(frame) to Rectangle(0, 0, 40, 20)),
                    windowBoundsFor = { Rectangle(0, 0, 40, 20) },
                    frameInsetsFor = { Insets(0, 0, 0, 0) },
                    backend = backend,
                    nativeWindowCaptureAvailable = true,
                )
            assertNull(hash)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `unsupported native absence uses region without throwing`() {
        assumeLiveAwtAvailable()
        val regionImage = solidImage(8, 8, 0xFF334455.toInt())
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { regionImage },
                nativeCapture = {
                    throw UnsupportedOperationException(
                        "Native window capture bridge is unavailable"
                    )
                },
            )
        val frame = Frame()
        try {
            val image =
                captureSurfaceForVisualIdle(
                    backend = backend,
                    window = tracked(frame),
                    surfaceRegion = Rectangle(0, 0, 8, 8),
                    windowBounds = Rectangle(0, 0, 8, 8),
                    frameInsets = Insets(0, 0, 0, 0),
                    nativeWindowCaptureAvailable = false,
                )
            assertSame(regionImage, image)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `native path does not region-fallback when host is not a Frame`() {
        assumeLiveAwtAvailable()
        // A bare Window (not Frame) cannot use native capture; when native is "available" we must
        // not silently substitute region — callers see null (unsampleable) instead.
        var regionCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCalls += 1
                    solidImage(1, 1, 0)
                },
                nativeCapture = { solidImage(10, 10, 0xFF0000FF.toInt()) },
            )
        val window =
            object : java.awt.Window(null as java.awt.Frame?) {
                init {
                    setBounds(0, 0, 10, 10)
                }
            }
        try {
            val image =
                captureSurfaceForVisualIdle(
                    backend = backend,
                    window = TrackedWindow("w", window, composePanel = null, isPopup = false),
                    surfaceRegion = Rectangle(0, 0, 10, 10),
                    windowBounds = Rectangle(0, 0, 10, 10),
                    frameInsets = Insets(0, 0, 0, 0),
                    nativeWindowCaptureAvailable = true,
                )
            assertNull(image)
            assertEquals(0, regionCalls)
        } finally {
            window.dispose()
        }
    }

    private fun tracked(frame: Frame): TrackedWindow =
        TrackedWindow("test", frame, composePanel = null, isPopup = false)

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
