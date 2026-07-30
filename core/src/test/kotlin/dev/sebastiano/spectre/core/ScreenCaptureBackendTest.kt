@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.core.capture.cropImageToScreenRegion
import java.awt.Frame
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ScreenCaptureBackendTest {

    @Test
    fun `optional native bridge is absent without recording on the classpath`() {
        val recordingFreeLoader =
            object : ClassLoader(null) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> =
                    throw ClassNotFoundException(name)
            }

        assertNull(nativeWindowCaptureFor(recordingFreeLoader))
    }

    @Test
    fun `optional native bridge is discovered without a core compile dependency`() {
        assertTrue(nativeWindowCaptureFor(javaClass.classLoader) != null)
    }

    @Test
    fun `headless driver disables platform capture routing`() {
        assertFalse(RobotDriver.headless().allowsPlatformCapture)
    }

    @Test
    fun `native client images crop in their client-window coordinate space`() {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(20, 30, 300, 200) }
        val clientBounds = Rectangle(20, 54, 300, 176)
        val image = BufferedImage(300, 176, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(10, 10, 0xFF112233.toInt())
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("should not fall back") },
                nativeCapture = { image },
                nativeCaptureBounds = { _, _, _, _ -> clientBounds },
            )
        try {
            val capture = backend.captureWindow(tracked(frame), Rectangle(frame.bounds))
            val crop =
                cropImageToScreenRegion(
                    capture.image,
                    Rectangle(30, 64, 1, 1),
                    capture.boundsOnScreen,
                )
            assertEquals(0xFF112233.toInt(), crop.getRGB(0, 0))
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `scaled Linux native client images retain client-window coordinates`() {
        val windowBounds = Rectangle(20, 30, 300, 200)
        val clientBounds =
            nativeWindowCaptureBounds(
                osName = "Linux",
                windowBounds = windowBounds,
                insets = Insets(24, 0, 0, 0),
                isWayland = false,
            )
        val image = BufferedImage(600, 352, BufferedImage.TYPE_INT_ARGB)
        image.setRGB(20, 20, 0xFF112233.toInt())

        val crop = cropImageToScreenRegion(image, Rectangle(30, 64, 1, 1), clientBounds)

        assertEquals(Rectangle(20, 54, 300, 176), clientBounds)
        assertEquals(0xFF112233.toInt(), crop.getRGB(0, 0))
    }

    @Test
    fun `Wayland native images retain full-frame coordinates`() {
        val windowBounds = Rectangle(20, 30, 300, 200)

        assertEquals(
            windowBounds,
            nativeWindowCaptureBounds(
                osName = "Linux",
                windowBounds = windowBounds,
                insets = Insets(24, 0, 0, 0),
                isWayland = true,
            ),
        )
    }

    @Test
    fun `Wayland detection recognizes a runtime-directory socket`() {
        assertTrue(
            isWaylandSession(
                getenv = { if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null },
                runtimeDirHasWaylandSocket = { it == Path.of("/run/user/1000") },
            )
        )
    }

    @Test
    fun `Wayland detection ignores an unreadable runtime directory`() {
        assertFalse(
            isWaylandSession(
                getenv = { if (it == "XDG_RUNTIME_DIR") "/run/user/1000" else null },
                runtimeDirHasWaylandSocket = { error("unreadable runtime directory") },
            )
        )
    }

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
            assertSame(expected, backend.captureWindow(tracked(frame)).image)
            assertEquals(0, fallbackCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `native helper images normalize to sRGB ARGB`() {
        val source =
            BufferedImage(2, 1, BufferedImage.TYPE_4BYTE_ABGR).apply {
                setRGB(0, 0, 0xFF113355.toInt())
            }
        val captured = normalizeNativeImage(source)
        assertEquals(BufferedImage.TYPE_INT_ARGB, captured.type)
        assertEquals(0xFF113355.toInt(), captured.getRGB(0, 0))
    }

    @Test
    fun `native unavailability fails without capturing a screen region`() {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(40, 50, 300, 200) }
        var regionCaptureCalls = 0
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = {
                    regionCaptureCalls += 1
                    error("screen-region capture must not be used")
                },
                nativeCapture = {
                    throw IllegalStateException("macOS window screenshot is unavailable")
                },
            )
        try {
            val error =
                assertFailsWith<IllegalStateException> { backend.captureWindow(tracked(frame)) }
            assertTrue(error.message.orEmpty().contains("macOS window screenshot is unavailable"))
            assertEquals(0, regionCaptureCalls)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `disabled native capture preserves the driver's loud failure`() {
        assumeLiveAwtAvailable()
        val frame = Frame().apply { setBounds(40, 50, 300, 200) }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture should be disabled") },
                nativeCaptureEnabled = { false },
            )
        try {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    backend.captureWindow(tracked(frame))
                }
            assertTrue(error.message.orEmpty().contains("disabled"))
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `duplicate window titles fail with matching window bounds`() {
        assumeLiveAwtAvailable()
        val first =
            Frame("same title").apply {
                setBounds(10, 20, 100, 80)
                addNotify()
            }
        val second =
            Frame("same title").apply {
                setBounds(120, 30, 100, 80)
                addNotify()
            }
        val backend =
            PlatformScreenCaptureBackend(
                regionCapture = { error("screen-region capture must not be used") },
                nativeCapture = { error("native capture should not be used for duplicate titles") },
            )
        try {
            val error =
                assertFailsWith<IllegalStateException> { backend.captureWindow(tracked(second)) }
            assertTrue(error.message.orEmpty().contains("same title"))
            assertTrue(
                error.message
                    .orEmpty()
                    .contains("java.awt.Rectangle[x=10,y=20,width=100,height=80]")
            )
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

    private fun tracked(frame: Frame): TrackedWindow =
        TrackedWindow("test", frame, composePanel = null, isPopup = false)
}
