@file:OptIn(ExperimentalSpectreRecordingApi::class)

package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.TitleDiscriminationAware
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoScreenshotterTest {

    @Test
    fun `captureWindow routes to SCK screenshotter on macOS`() {
        val sck = StubWindowScreenshotter()
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                sckScreenshotter = sck,
                windowsWindowScreenshotter = windows,
                isMacOs = { true },
            )
        val window = StubScreenshotWindow(title = "MyApp")

        screenshotter.captureWindow(window)

        assertEquals(1, sck.captureCallCount)
        assertSame(window, sck.lastWindow)
        assertEquals(0, windows.captureCallCount)
    }

    @Test
    fun `captureWindow wraps missing SCK helper as a clear macOS failure`() {
        val sck = StubWindowScreenshotter(behavior = StubScreenshotBehavior.HelperNotBundled)
        val screenshotter = autoScreenshotter(sckScreenshotter = sck, isMacOs = { true })

        val error =
            assertFailsWith<IllegalStateException> {
                screenshotter.captureWindow(StubScreenshotWindow(title = "MyApp"))
            }

        assertTrue(error.message.orEmpty().contains("macOS window screenshot"))
        assertTrue(error.message.orEmpty().contains("helper"))
    }

    @Test
    fun `captureWindow routes to Windows title screenshotter on Windows`() {
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )
        val window = StubScreenshotWindow(title = "MyApp")

        screenshotter.captureWindow(window)

        assertEquals(1, windows.captureCallCount)
        assertSame(window, windows.lastWindow)
    }

    @Test
    fun `captureWindow temporarily disambiguates blank Windows titles`() {
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )

        val window = StubScreenshotWindow(title = "")

        screenshotter.captureWindow(window)

        assertEquals(1, windows.captureCallCount)
        assertTrue(windows.titleDuringCapture.orEmpty().isNotBlank())
        assertEquals("", window.title)
    }

    @Test
    fun `captureWindow restores title while preserving interruption`() {
        val windows = StubWindowScreenshotter(behavior = StubScreenshotBehavior.Interrupted)
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )
        val window = StubScreenshotWindow(title = "")

        try {
            assertFailsWith<InterruptedException> { screenshotter.captureWindow(window) }
            assertEquals("", window.title)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `captureWindow never renames an untitled framed window on Windows`() {
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )
        val window =
            FramedWindow(
                logicalFrame = Rectangle(10, 20, 300, 200),
                deviceFrame = Rectangle(10, 20, 300, 200),
            )

        screenshotter.captureWindow(window)

        assertSame(window, windows.lastWindow)
        assertEquals(null, windows.titleDuringCapture)
    }

    @Test
    fun `captureWindow never renames an untitled framed window on Linux X11`() {
        val linux = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                linuxWindowScreenshotter = linux,
                isMacOs = { false },
                isLinux = { true },
            )
        val window =
            FramedWindow(
                logicalFrame = Rectangle(10, 20, 300, 200),
                deviceFrame = Rectangle(10, 20, 300, 200),
            )

        screenshotter.captureWindow(window)

        assertSame(window, linux.lastWindow)
        assertEquals(null, linux.titleDuringCapture)
    }

    @Test
    fun `captureWindow routes to Linux helper on Linux X11`() {
        val linux = StubWindowScreenshotter()
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                linuxWindowScreenshotter = linux,
                isMacOs = { false },
                isWindows = { false },
                isLinux = { true },
                isWayland = { false },
            )
        val bounds = Rectangle(12, 34, 56, 78)

        screenshotter.captureWindow(StubScreenshotWindow(title = "MyApp", bounds = bounds))

        assertEquals(1, linux.captureCallCount)
        assertEquals("MyApp", linux.lastWindow?.title)
        assertEquals(0, windows.captureCallCount)
    }

    @Test
    fun `captureWindow routes to Linux helper on Wayland`() {
        val linux = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                linuxWindowScreenshotter = linux,
                isMacOs = { false },
                isLinux = { true },
                isWayland = { true },
            )

        screenshotter.captureWindow(StubScreenshotWindow(title = "MyApp"))

        assertEquals(1, linux.captureCallCount)
        assertEquals("MyApp", linux.lastWindow?.title)
    }

    @Test
    fun `captureWindow fails loudly on unsupported platforms`() {
        val screenshotter = autoScreenshotter(isMacOs = { false })

        val error =
            assertFailsWith<UnsupportedOperationException> {
                screenshotter.captureWindow(StubScreenshotWindow(title = "MyApp"))
            }

        assertTrue(error.message.orEmpty().contains("unsupported"))
    }

    @Test
    fun `captureWindowByTitle targets another process's window on macOS without renaming it`() {
        val sck = StubWindowScreenshotter()
        val screenshotter = autoScreenshotter(sckScreenshotter = sck, isMacOs = { true })

        screenshotter.captureWindowByTitle(title = "Remote App", windowOwnerPid = 4242L)

        assertEquals(1, sck.captureCallCount)
        assertEquals("Remote App", sck.lastWindow?.title)
        assertEquals(4242L, sck.lastWindowOwnerPid)
        // A discriminator would rename a window object in this JVM, not the remote window, and
        // the helper would then search the target process for a title that does not exist.
        assertEquals(
            false,
            (sck.lastWindow as? TitleDiscriminationAware)?.requiresTitleDiscriminator,
        )
    }

    @Test
    fun `captureWindowByTitle routes to the Windows helper with the owner pid`() {
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )

        screenshotter.captureWindowByTitle(title = "Remote App", windowOwnerPid = 4242L)

        assertEquals(1, windows.captureCallCount)
        assertEquals("Remote App", windows.lastWindow?.title)
        assertEquals(4242L, windows.lastWindowOwnerPid)
    }

    @Test
    fun `captureWindowByFrame selects another process's untitled window by points on macOS`() {
        val sck = StubWindowScreenshotter()
        val screenshotter = autoScreenshotter(sckScreenshotter = sck, isMacOs = { true })

        screenshotter.captureWindowByFrame(
            windowBounds = Rectangle(100, 120, 240, 160),
            screenBounds = Rectangle(0, 0, 1728, 1117),
            scaleX = 2.0,
            scaleY = 2.0,
            windowOwnerPid = 4242L,
        )

        val framed = assertIs<FramedWindow>(sck.lastWindow)
        assertEquals(Rectangle(100, 120, 240, 160), framed.logicalFrame)
        assertEquals(4242L, sck.lastWindowOwnerPid)
    }

    @Test
    fun `captureWindowByFrame selects another process's untitled window by device pixels on Windows`() {
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )

        // A 1.5x monitor to the right of a 1920px primary.
        screenshotter.captureWindowByFrame(
            windowBounds = Rectangle(2000, 100, 300, 200),
            screenBounds = Rectangle(1920, 0, 1707, 960),
            scaleX = 1.5,
            scaleY = 1.5,
            windowOwnerPid = 4242L,
        )

        val framed = assertIs<FramedWindow>(windows.lastWindow)
        assertEquals(Rectangle(2040, 150, 450, 300), framed.deviceFrame)
        assertEquals(4242L, windows.lastWindowOwnerPid)
        assertEquals(null, windows.titleDuringCapture)
    }

    @Test
    fun `captureWindowByFrame is unsupported on Linux`() {
        val linux = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                linuxWindowScreenshotter = linux,
                isMacOs = { false },
                isLinux = { true },
            )

        assertFailsWith<UnsupportedOperationException> {
            screenshotter.captureWindowByFrame(
                windowBounds = Rectangle(0, 0, 240, 160),
                screenBounds = Rectangle(0, 0, 1920, 1080),
                scaleX = 1.0,
                scaleY = 1.0,
                windowOwnerPid = 4242L,
            )
        }
        assertEquals(0, linux.captureCallCount)
    }

    @Test
    fun `captureWindowByFrame rejects an empty frame`() {
        val screenshotter = autoScreenshotter(isMacOs = { true })

        assertFailsWith<IllegalArgumentException> {
            screenshotter.captureWindowByFrame(
                windowBounds = Rectangle(0, 0, 0, 160),
                screenBounds = Rectangle(0, 0, 1920, 1080),
                scaleX = 1.0,
                scaleY = 1.0,
                windowOwnerPid = 4242L,
            )
        }
    }

    @Test
    fun `captureWindowByTitle rejects a blank title`() {
        val screenshotter = autoScreenshotter(isMacOs = { true })

        assertFailsWith<IllegalArgumentException> {
            screenshotter.captureWindowByTitle(title = " ", windowOwnerPid = 4242L)
        }
    }

    @Test
    fun `captureWindowByTitle is unsupported on Linux`() {
        val linux = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                linuxWindowScreenshotter = linux,
                isMacOs = { false },
                isLinux = { true },
            )

        assertFailsWith<UnsupportedOperationException> {
            screenshotter.captureWindowByTitle(title = "Remote App", windowOwnerPid = 4242L)
        }
        assertEquals(0, linux.captureCallCount)
    }
}

private fun autoScreenshotter(
    sckScreenshotter: WindowScreenshotter = StubWindowScreenshotter(),
    windowsWindowScreenshotter: WindowScreenshotter? = null,
    linuxWindowScreenshotter: WindowScreenshotter? = null,
    isMacOs: () -> Boolean,
    isWindows: () -> Boolean = { false },
    isLinux: () -> Boolean = { false },
    isWayland: () -> Boolean = { false },
): AutoScreenshotter =
    AutoScreenshotter(
        sckScreenshotter,
        windowsWindowScreenshotter,
        linuxWindowScreenshotter,
        isMacOs,
        isWindows,
        isLinux,
        isWayland,
    )

private enum class StubScreenshotBehavior {
    Succeeds,
    HelperNotBundled,
    Interrupted,
}

private class StubWindowScreenshotter(
    private val behavior: StubScreenshotBehavior = StubScreenshotBehavior.Succeeds
) : WindowScreenshotter {
    var captureCallCount: Int = 0
        private set

    var lastWindow: TitledWindow? = null
        private set

    var titleDuringCapture: String? = null
        private set

    var lastWindowOwnerPid: Long? = null
        private set

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        captureCallCount += 1
        lastWindow = window
        lastWindowOwnerPid = windowOwnerPid
        titleDuringCapture = window.title
        return when (behavior) {
            StubScreenshotBehavior.Succeeds -> BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            StubScreenshotBehavior.HelperNotBundled ->
                throw HelperNotBundledException("test helper is missing")
            StubScreenshotBehavior.Interrupted -> {
                Thread.currentThread().interrupt()
                throw InterruptedException("test interruption")
            }
        }
    }
}

private class StubScreenshotWindow(
    override var title: String?,
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100),
) : TitledWindow
