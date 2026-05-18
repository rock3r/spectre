package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoScreenshotterTest {

    @Test
    fun `captureWindow routes to SCK screenshotter on macOS`() {
        val sck = StubWindowScreenshotter()
        val windows = StubWindowScreenshotter()
        val x11 = StubRegionScreenshotter()
        val screenshotter =
            autoScreenshotter(
                sckScreenshotter = sck,
                windowsWindowScreenshotter = windows,
                x11RegionScreenshotter = x11,
                isMacOs = { true },
            )
        val window = StubScreenshotWindow(title = "MyApp")

        screenshotter.captureWindow(window)

        assertEquals(1, sck.captureCallCount)
        assertSame(window, sck.lastWindow)
        assertEquals(0, windows.captureCallCount)
        assertFalse(x11.captureCalled)
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
        val x11 = StubRegionScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                x11RegionScreenshotter = x11,
                isMacOs = { false },
                isWindows = { true },
            )
        val window = StubScreenshotWindow(title = "MyApp")

        screenshotter.captureWindow(window)

        assertEquals(1, windows.captureCallCount)
        assertSame(window, windows.lastWindow)
        assertFalse(x11.captureCalled)
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
    fun `captureWindow uses explicit X11 region fallback on Linux X11`() {
        val x11 = StubRegionScreenshotter()
        val windows = StubWindowScreenshotter()
        val screenshotter =
            autoScreenshotter(
                windowsWindowScreenshotter = windows,
                x11RegionScreenshotter = x11,
                isMacOs = { false },
                isWindows = { false },
                isLinux = { true },
                isWayland = { false },
            )
        val bounds = Rectangle(12, 34, 56, 78)

        screenshotter.captureWindow(StubScreenshotWindow(title = "MyApp", bounds = bounds))

        assertEquals(bounds, x11.lastRegion)
        assertEquals(0, windows.captureCallCount)
    }

    @Test
    fun `captureWindow fails loudly on Wayland still screenshots`() {
        val screenshotter =
            autoScreenshotter(isMacOs = { false }, isLinux = { true }, isWayland = { true })

        val error =
            assertFailsWith<UnsupportedOperationException> {
                screenshotter.captureWindow(StubScreenshotWindow(title = "MyApp"))
            }

        assertTrue(error.message.orEmpty().contains("Wayland"))
        assertTrue(error.message.orEmpty().contains("window-targeted recording"))
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
    x11RegionScreenshotter: RegionScreenshotter = StubRegionScreenshotter(),
    isMacOs: () -> Boolean,
    isWindows: () -> Boolean = { false },
    isLinux: () -> Boolean = { false },
    isWayland: () -> Boolean = { false },
): AutoScreenshotter =
    AutoScreenshotter(
        sckScreenshotter,
        windowsWindowScreenshotter,
        x11RegionScreenshotter,
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

private class StubRegionScreenshotter : RegionScreenshotter {
    var captureCalled: Boolean = false
        private set

    var lastRegion: Rectangle? = null
        private set

    override fun captureRegion(region: Rectangle): BufferedImage {
        captureCalled = true
        lastRegion = region
        return BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
    }
}

private class StubScreenshotWindow(
    override var title: String?,
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100),
) : TitledWindow
