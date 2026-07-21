package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandWindowSourceRecorder
import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import dev.sebastiano.spectre.recording.windows.WindowsGraphicsCaptureHelperNotBundledException
import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AutoRecorderTest {

    @Test
    fun `startRegion routes to ScreenCaptureKit on macOS`() {
        val ffmpeg = StubRegionRecorder()
        val sckRegion = StubRegionRecorder()
        val recorder = autoRecorder(macOsRegionRecorder = sckRegion, isMacOs = { true })
        val output = tempMov()
        try {
            val region = Rectangle(0, 0, 100, 100)

            recorder.startRegion(region = region, output = output)

            assertEquals(region, sckRegion.lastRegion)
            assertFalse(ffmpeg.startCalled, "macOS region capture must not fall through to ffmpeg")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion fails loudly on macOS when SCK helper is not bundled`() {
        val ffmpeg = StubRegionRecorder()
        val recorder =
            autoRecorder(macOsRegionRecorder = MissingSckHelperRecorder(), isMacOs = { true })
        val output = tempMov()
        try {
            val region = Rectangle(0, 0, 100, 100)

            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startRegion(region = region, output = output)
                }

            assertTrue(error.message.orEmpty().contains("macOS region capture"))
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion routes to Windows Graphics Capture on Windows`() {
        val ffmpeg = StubRegionRecorder()
        val windowsRegion = StubRegionRecorder()
        val recorder =
            autoRecorder(
                windowsRegionRecorder = windowsRegion,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            val region = Rectangle(10, 20, 300, 200)

            recorder.startRegion(region = region, output = output)

            assertEquals(region, windowsRegion.lastRegion)
            assertFalse(
                ffmpeg.startCalled,
                "Windows region capture must not fall through to ffmpeg",
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion rejects Windows custom ffmpeg options instead of falling back to ffmpeg`() {
        val ffmpeg = StubRegionRecorder()
        val windowsRegion = UnsupportedWindowsOptionsRecorder()
        val recorder =
            autoRecorder(
                windowsRegionRecorder = windowsRegion,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            val region = Rectangle(10, 20, 300, 200)
            val options = RecordingOptions(codec = "libx264rgb")

            val error =
                assertFailsWith<IllegalArgumentException> {
                    recorder.startRegion(region = region, output = output, options = options)
                }

            assertTrue(error.message.orEmpty().contains("custom RecordingOptions.codec"))
            assertFalse(ffmpeg.startCalled)
            assertTrue(windowsRegion.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion fails loudly on Windows when WGC helper is not bundled`() {
        val ffmpeg = StubRegionRecorder()
        val recorder =
            autoRecorder(
                windowsRegionRecorder = MissingWindowsHelperRecorder(),
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            val region = Rectangle(10, 20, 300, 200)

            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startRegion(region = region, output = output)
                }

            assertTrue(error.message.orEmpty().contains("Windows region capture"))
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion fails loudly when Windows region recorder is unavailable`() {
        val ffmpeg = StubRegionRecorder()
        val recorder =
            autoRecorder(windowsRegionRecorder = null, isMacOs = { false }, isWindows = { true })
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startRegion(Rectangle(0, 0, 100, 100), output)
                }

            assertTrue(error.message.orEmpty().contains("Windows region capture"))
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion routes to Wayland region portal on Wayland hosts`() {
        val ffmpeg = StubRegionRecorder()
        val linux = StubRegionRecorder()
        val portal = StubRegionRecorder()
        val recorder =
            autoRecorder(
                linuxRegionRecorder = linux,
                waylandPortalRecorder = portal,
                isMacOs = { false },
                isLinux = { true },
                isWayland = { true },
            )
        val output = tempMov()
        try {
            val region = Rectangle(0, 0, 100, 100)

            recorder.startRegion(region = region, output = output)

            assertEquals(region, portal.lastRegion)
            assertFalse(
                ffmpeg.startCalled,
                "Wayland region capture must not fall through to ffmpeg",
            )
            assertFalse(linux.startCalled, "Wayland region capture must use the portal recorder")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion routes to Linux helper on Linux X11 hosts`() {
        val ffmpeg = StubRegionRecorder()
        val linux = StubRegionRecorder()
        val recorder =
            autoRecorder(
                linuxRegionRecorder = linux,
                isMacOs = { false },
                isLinux = { true },
                isWayland = { false },
            )
        val output = tempMov()
        try {
            val region = Rectangle(20, 30, 160, 90)

            recorder.startRegion(region = region, output = output)

            assertEquals(region, linux.lastRegion)
            assertFalse(ffmpeg.startCalled, "Linux X11 region capture must not use ffmpeg")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion fails loudly when Wayland region portal is unavailable`() {
        val failure = IllegalStateException("gst-launch not found")
        val recorder =
            autoRecorder(
                waylandPortalRecorder = null,
                waylandPortalRecorderFailure = failure,
                isMacOs = { false },
                isWayland = { true },
            )
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startRegion(Rectangle(0, 0, 100, 100), output)
                }

            assertTrue(error.message.orEmpty().contains("Wayland region capture"))
            assertSame(failure, error.cause)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow routes to SCK on macOS`() {
        val sck = StubWindowRecorder(name = "sck")
        val ffmpeg = StubRegionRecorder()
        val recorder = autoRecorder(sckRecorder = sck, isMacOs = { true })
        val output = tempMov()
        try {
            val window = StubTitledWindow(title = "MyApp")

            recorder.startWindow(window = window, output = output)

            assertEquals(1, sck.startCallCount)
            assertFalse(ffmpeg.startCalled, "Window capture must not silently fall back to region")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow with crop routes to startCropped on macOS and not region`() {
        val sck = StubWindowRecorder(name = "sck")
        val ffmpeg = StubRegionRecorder()
        val recorder = autoRecorder(sckRecorder = sck, isMacOs = { true })
        val output = tempMov()
        val crop = Rectangle(10, 40, 200, 150)
        try {
            recorder.startWindow(
                window = StubTitledWindow(title = "Host"),
                output = output,
                cropInWindow = crop,
                scaleX = 2.0,
                scaleY = 2.0,
            )
            assertEquals(0, sck.startCallCount)
            assertEquals(1, sck.startCroppedCallCount)
            assertEquals(crop, sck.lastCrop)
            assertEquals(2.0, sck.lastScaleX)
            assertEquals(2.0, sck.lastScaleY)
            assertFalse(ffmpeg.startCalled, "window+crop must not degrade to region")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow with crop routes to startCropped on Windows`() {
        val windowRecorder = StubWindowRecorder(name = "wgc")
        val ffmpeg = StubRegionRecorder()
        val recorder =
            autoRecorder(
                windowsWindowRecorder = windowRecorder,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        val crop = Rectangle(8, 32, 640, 480)
        try {
            recorder.startWindow(
                window = StubTitledWindow(title = "Host"),
                output = output,
                cropInWindow = crop,
                scaleX = 1.25,
                scaleY = 1.25,
            )
            assertEquals(1, windowRecorder.startCroppedCallCount)
            assertEquals(crop, windowRecorder.lastCrop)
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow with crop fails loudly on Linux instead of region fallback`() {
        val ffmpeg = StubRegionRecorder()
        val linuxWindow = StubWindowRecorder(name = "linux")
        val recorder =
            autoRecorder(
                linuxWindowRecorder = linuxWindow,
                isMacOs = { false },
                isWindows = { false },
                isLinux = { true },
                isWayland = { false },
            )
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startWindow(
                        window = StubTitledWindow(title = "Host"),
                        output = output,
                        cropInWindow = Rectangle(0, 0, 100, 100),
                    )
                }
            assertTrue(error.message.orEmpty().contains("not available on this Linux"))
            assertEquals(0, linuxWindow.startCallCount)
            assertEquals(0, linuxWindow.startCroppedCallCount)
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow propagates missing SCK helper as loud window-capture failure`() {
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.HelperNotBundled)
        val ffmpeg = StubRegionRecorder()
        val recorder = autoRecorder(sckRecorder = sck, isMacOs = { true })
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startWindow(
                        window = StubTitledWindow(title = "MyApp"),
                        output = output,
                    )
                }

            assertTrue(error.message.orEmpty().contains("macOS window capture"))
            assertFalse(ffmpeg.startCalled, "Helper failure must not degrade to region capture")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow routes to Windows title recorder on Windows`() {
        val ffmpeg = StubRegionRecorder()
        val windowRecorder = StubWindowRecorder(name = "ffmpegWindow")
        val recorder =
            autoRecorder(
                windowsWindowRecorder = windowRecorder,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            recorder.startWindow(window = StubTitledWindow(title = "MyApp"), output = output)

            assertEquals(1, windowRecorder.startCallCount)
            assertFalse(ffmpeg.startCalled, "Window capture must not silently fall back to region")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow rejects blank Windows titles instead of falling back to region`() {
        val ffmpeg = StubRegionRecorder()
        val windowRecorder = StubWindowRecorder(name = "ffmpegWindow")
        val recorder =
            autoRecorder(
                windowsWindowRecorder = windowRecorder,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    recorder.startWindow(window = StubTitledWindow(title = ""), output = output)
                }

            assertTrue(error.message.orEmpty().contains("non-blank window title"))
            assertEquals(0, windowRecorder.startCallCount)
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow routes to Wayland window portal with window bounds`() {
        val ffmpeg = StubRegionRecorder()
        val linuxWindow = StubWindowRecorder(name = "linux")
        val portalRegion = StubRegionRecorder()
        val portalWindow = StubWaylandWindowSourceRecorder()
        val recorder =
            autoRecorder(
                linuxWindowRecorder = linuxWindow,
                waylandPortalRecorder = portalRegion,
                waylandPortalWindowRecorder = portalWindow,
                isMacOs = { false },
                isLinux = { true },
                isWayland = { true },
            )
        val output = tempMov()
        try {
            val bounds = Rectangle(5, 6, 100, 120)
            val window = StubTitledWindow(title = "MyApp", bounds = bounds)

            recorder.startWindow(window = window, output = output)

            assertEquals(1, portalWindow.startCallCount)
            assertSame(window, portalWindow.lastWindow)
            assertEquals(bounds, portalWindow.lastRegion)
            assertFalse(portalRegion.startCalled, "Window mode must not use region portal")
            assertEquals(0, linuxWindow.startCallCount)
            assertFalse(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow routes to Linux helper on Linux X11 hosts`() {
        val ffmpeg = StubRegionRecorder()
        val linuxWindow = StubWindowRecorder(name = "linux")
        val recorder =
            autoRecorder(
                linuxWindowRecorder = linuxWindow,
                isMacOs = { false },
                isLinux = { true },
                isWayland = { false },
            )
        val output = tempMov()
        try {
            val window = StubTitledWindow(title = "MyApp")

            recorder.startWindow(window = window, output = output)

            assertEquals(1, linuxWindow.startCallCount)
            assertFalse(ffmpeg.startCalled, "Linux X11 window capture must not use ffmpeg")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow fails loudly when Wayland window portal is unavailable`() {
        val failure = IllegalStateException("xprop not found")
        val recorder =
            autoRecorder(
                waylandPortalWindowRecorder = null,
                waylandPortalWindowRecorderFailure = failure,
                isMacOs = { false },
                isWayland = { true },
            )
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startWindow(
                        window = StubTitledWindow(title = "MyApp"),
                        output = output,
                    )
                }

            assertTrue(error.message.orEmpty().contains("Wayland window capture"))
            assertSame(failure, error.cause)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startWindow fails loudly on unsupported window-capture platforms`() {
        val ffmpeg = StubRegionRecorder()
        val recorder = autoRecorder(isMacOs = { false })
        val output = tempMov()
        try {
            val error =
                assertFailsWith<IllegalStateException> {
                    recorder.startWindow(
                        window = StubTitledWindow(title = "MyApp"),
                        output = output,
                    )
                }

            assertTrue(error.message.orEmpty().contains("unsupported"))
            assertFalse(ffmpeg.startCalled, "Unsupported window mode must not fall back to region")
        } finally {
            output.deleteIfExists()
        }
    }

    private fun tempMov(): Path = Files.createTempFile("spectre-auto-recorder-test-", ".mov")
}

private fun autoRecorder(
    sckRecorder: WindowRecorder = StubWindowRecorder(name = "sck"),
    windowsWindowRecorder: WindowRecorder? = null,
    windowsRegionRecorder: Recorder? = null,
    linuxRegionRecorder: Recorder? = null,
    linuxWindowRecorder: WindowRecorder? = null,
    macOsRegionRecorder: Recorder = StubRegionRecorder(),
    waylandPortalRecorder: Recorder? = null,
    waylandPortalWindowRecorder: WaylandWindowSourceRecorder? = null,
    waylandPortalRecorderFailure: Throwable? = null,
    waylandPortalWindowRecorderFailure: Throwable? = null,
    isMacOs: () -> Boolean,
    isWindows: () -> Boolean = { false },
    isLinux: () -> Boolean = { false },
    isWayland: () -> Boolean = { false },
): AutoRecorder =
    AutoRecorder(
        sckRecorder,
        macOsRegionRecorder,
        windowsWindowRecorder,
        windowsRegionRecorder,
        linuxRegionRecorder,
        linuxWindowRecorder,
        waylandPortalRecorder,
        waylandPortalWindowRecorder,
        waylandPortalRecorderFailure,
        waylandPortalWindowRecorderFailure,
        isMacOs,
        isWindows,
        isLinux,
        isWayland,
    )

private enum class StubBehavior {
    Succeeds,
    HelperNotBundled,
    RuntimeFailure,
    ShouldNeverBeCalled,
}

private class StubWindowRecorder(
    private val name: String,
    private val behavior: StubBehavior = StubBehavior.Succeeds,
) : WindowRecorder {
    var startCallCount: Int = 0
        private set

    var startCroppedCallCount: Int = 0
        private set

    var lastCrop: Rectangle? = null
        private set

    var lastScaleX: Double? = null
        private set

    var lastScaleY: Double? = null
        private set

    override fun start(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCallCount += 1
        return when (behavior) {
            StubBehavior.Succeeds -> NoopHandle(output)
            StubBehavior.HelperNotBundled ->
                throw HelperNotBundledException("test [$name]: helper not bundled")
            StubBehavior.RuntimeFailure -> error("test [$name]: runtime failure")
            StubBehavior.ShouldNeverBeCalled ->
                error("StubWindowRecorder[$name] was not supposed to be invoked in this test")
        }
    }

    override fun startCropped(
        window: TitledWindow,
        cropInWindow: Rectangle,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
        scaleX: Double,
        scaleY: Double,
    ): RecordingHandle {
        startCroppedCallCount += 1
        lastCrop = Rectangle(cropInWindow)
        lastScaleX = scaleX
        lastScaleY = scaleY
        return when (behavior) {
            StubBehavior.Succeeds -> NoopHandle(output)
            StubBehavior.HelperNotBundled ->
                throw HelperNotBundledException("test [$name]: helper not bundled")
            StubBehavior.RuntimeFailure -> error("test [$name]: runtime failure")
            StubBehavior.ShouldNeverBeCalled ->
                error("StubWindowRecorder[$name] startCropped was not supposed to be invoked")
        }
    }
}

private class StubRegionRecorder : Recorder {
    var startCalled: Boolean = false
        private set

    var lastRegion: Rectangle? = null
        private set

    var lastOptions: RecordingOptions? = null
        private set

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCalled = true
        lastRegion = region
        lastOptions = options
        return NoopHandle(output)
    }
}

private class MissingSckHelperRecorder : Recorder {
    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        throw HelperNotBundledException("test: helper not bundled")
    }
}

private class MissingWindowsHelperRecorder : Recorder {
    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        throw WindowsGraphicsCaptureHelperNotBundledException("missing helper")
    }
}

private class UnsupportedWindowsOptionsRecorder : Recorder {
    var startCalled: Boolean = false
        private set

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCalled = true
        throw IllegalArgumentException(
            "WindowsGraphicsCaptureRecorder does not support custom RecordingOptions.codec; " +
                "got ${options.codec}."
        )
    }
}

private class StubWaylandWindowSourceRecorder : WaylandWindowSourceRecorder {
    var startCallCount: Int = 0
        private set

    var lastWindow: TitledWindow? = null
        private set

    var lastRegion: Rectangle? = null
        private set

    override fun start(
        window: TitledWindow,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCallCount += 1
        lastWindow = window
        lastRegion = region
        return NoopHandle(output)
    }
}

private class StubTitledWindow(
    override var title: String? = "MyApp",
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100),
) : TitledWindow

private class NoopHandle(override val output: Path) : RecordingHandle {
    override val isStopped: Boolean = false

    override fun stop() = Unit
}
