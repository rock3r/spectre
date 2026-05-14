package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandWindowSourceRecorder
import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
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
    fun `startRegion routes to ffmpeg on non-Wayland hosts`() {
        val ffmpeg = StubRegionRecorder()
        val recorder = autoRecorder(ffmpegRecorder = ffmpeg, isMacOs = { true })
        val output = tempMov()
        try {
            val region = Rectangle(0, 0, 100, 100)

            recorder.startRegion(region = region, output = output)

            assertEquals(region, ffmpeg.lastRegion)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startRegion routes to Wayland region portal on Wayland hosts`() {
        val ffmpeg = StubRegionRecorder()
        val portal = StubRegionRecorder()
        val recorder =
            autoRecorder(
                ffmpegRecorder = ffmpeg,
                waylandPortalRecorder = portal,
                isMacOs = { false },
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
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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
    fun `startWindow propagates missing SCK helper as loud window-capture failure`() {
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.HelperNotBundled)
        val ffmpeg = StubRegionRecorder()
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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
                ffmpegRecorder = ffmpeg,
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
                ffmpegRecorder = ffmpeg,
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
        val portalRegion = StubRegionRecorder()
        val portalWindow = StubWaylandWindowSourceRecorder()
        val recorder =
            autoRecorder(
                ffmpegRecorder = ffmpeg,
                waylandPortalRecorder = portalRegion,
                waylandPortalWindowRecorder = portalWindow,
                isMacOs = { false },
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
            assertFalse(ffmpeg.startCalled)
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
        val recorder = autoRecorder(ffmpegRecorder = ffmpeg, isMacOs = { false })
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
    ffmpegRecorder: Recorder = StubRegionRecorder(),
    windowsWindowRecorder: WindowRecorder? = null,
    waylandPortalRecorder: Recorder? = null,
    waylandPortalWindowRecorder: WaylandWindowSourceRecorder? = null,
    waylandPortalRecorderFailure: Throwable? = null,
    waylandPortalWindowRecorderFailure: Throwable? = null,
    isMacOs: () -> Boolean,
    isWindows: () -> Boolean = { false },
    isWayland: () -> Boolean = { false },
): AutoRecorder =
    AutoRecorder(
        sckRecorder,
        ffmpegRecorder,
        windowsWindowRecorder,
        waylandPortalRecorder,
        waylandPortalWindowRecorder,
        waylandPortalRecorderFailure,
        waylandPortalWindowRecorderFailure,
        isMacOs,
        isWindows,
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
            StubBehavior.RuntimeFailure ->
                throw IllegalStateException("test [$name]: runtime failure")
            StubBehavior.ShouldNeverBeCalled ->
                error("StubWindowRecorder[$name] was not supposed to be invoked in this test")
        }
    }
}

private class StubRegionRecorder : Recorder {
    var startCalled: Boolean = false
        private set

    var lastRegion: Rectangle? = null
        private set

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCalled = true
        lastRegion = region
        return NoopHandle(output)
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
