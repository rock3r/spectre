package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
    fun `captureWindow rejects blank Windows titles`() {
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                isMacOs = { false },
                isWindows = { true },
            )

        val error =
            assertFailsWith<IllegalArgumentException> {
                screenshotter.captureWindow(StubScreenshotWindow(title = ""))
            }

        assertTrue(error.message.orEmpty().contains("non-blank window title"))
        assertEquals(0, windows.captureCallCount)
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
}

private class StubWindowScreenshotter(
    private val behavior: StubScreenshotBehavior = StubScreenshotBehavior.Succeeds
) : WindowScreenshotter {
    var captureCallCount: Int = 0
        private set

    var lastWindow: TitledWindow? = null
        private set

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        captureCallCount += 1
        lastWindow = window
        return when (behavior) {
            StubScreenshotBehavior.Succeeds -> BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB)
            StubScreenshotBehavior.HelperNotBundled ->
                throw HelperNotBundledException("test helper is missing")
        }
    }
}

private class StubScreenshotWindow(
    override var title: String?,
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100),
) : TitledWindow
