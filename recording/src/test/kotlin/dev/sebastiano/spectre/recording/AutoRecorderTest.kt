package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.awt.Rectangle
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AutoRecorderTest {

    @Test
    fun `null window always routes to ffmpeg regardless of OS or helper availability`() {
        val sck = StubWindowRecorder(name = "sck")
        val ffmpeg = StubFfmpegRecorder()
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
        val output = tempMov()
        try {
            recorder.start(window = null, region = Rectangle(0, 0, 100, 100), output = output)

            assertTrue(ffmpeg.startCalled, "Should fall back to ffmpeg when no window provided")
            assertEquals(0, sck.startCallCount)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `non-mac non-Windows host with a window routes to ffmpeg region capture`() {
        // Linux today; v4 follow-up may add a window-targeted X11/Wayland recorder. Until then
        // any non-mac/non-Windows host must fall through to region capture even when a window
        // is supplied.
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow(title = "MyApp")
        val recorder =
            autoRecorder(
                sckRecorder = sck,
                ffmpegRecorder = ffmpeg,
                windowsWindowRecorder = null,
                isMacOs = { false },
                isWindows = { false },
            )
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertTrue(ffmpeg.startCalled)
            assertEquals(0, sck.startCallCount)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `mac host with a window routes to SCK`() {
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.Succeeds)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow(title = "MyApp")
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertEquals(1, sck.startCallCount)
            assertEquals(false, ffmpeg.startCalled, "Should not fall back when SCK succeeds")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `helper-not-bundled triggers fallback to ffmpeg`() {
        // The cross-platform-jar case: built on Linux, run on macOS — helper isn't in the
        // jar, but the user has ffmpeg available. AutoRecorder should silently degrade to
        // region capture rather than throwing.
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.HelperNotBundled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow(title = "MyApp")
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertEquals(1, sck.startCallCount, "SCK is attempted first")
            assertTrue(ffmpeg.startCalled, "Falls back to ffmpeg when helper isn't bundled")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `non-fallback SCK failures (TCC, window-not-found) propagate without falling back`() {
        // If SCK is bundled but fails for a caller-actionable reason (no Screen Recording
        // permission, window doesn't exist), the user should see that error — falling back
        // would mask a real configuration mistake.
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.RuntimeFailure)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow(title = "MyApp")
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
        val output = tempMov()
        try {
            assertFailsWith<IllegalStateException> {
                recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)
            }
            assertEquals(1, sck.startCallCount)
            assertEquals(false, ffmpeg.startCalled, "Real SCK failures must not silently fall back")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `helper-not-bundled fallback writes a warning to stderr so the degradation is visible`() {
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.HelperNotBundled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow(title = "MyApp")
        val recorder = autoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
        val output = tempMov()
        val originalErr = System.err
        val captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured))
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)
        } finally {
            System.setErr(originalErr)
            output.deleteIfExists()
        }
        val warning = captured.toString(Charsets.UTF_8)
        assertTrue(
            warning.contains("AutoRecorder", ignoreCase = true) ||
                warning.contains("falling back", ignoreCase = true),
            "Expected stderr fallback warning, got: $warning",
        )
    }

    @Test
    fun `Windows host with non-blank window title routes to FfmpegWindowRecorder`() {
        // Issue #55: when the host is Windows and the caller supplies a TitledWindow whose
        // title is usable for gdigrab `-i title=...`, AutoRecorder hands the window-targeted
        // recorder rather than dropping back to region capture — same shape as SCK on macOS.
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val winWindowRecorder =
            StubWindowRecorder(name = "ffmpegWindow", behavior = StubBehavior.Succeeds)
        val window = StubTitledWindow(title = "MyApp")
        val recorder =
            autoRecorder(
                sckRecorder = sck,
                ffmpegRecorder = ffmpeg,
                windowsWindowRecorder = winWindowRecorder,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertEquals(1, winWindowRecorder.startCallCount, "Windows window recorder should fire")
            assertEquals(
                false,
                ffmpeg.startCalled,
                "Region path must not fire when title is usable",
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `Windows host with blank window title falls back to ffmpeg region capture`() {
        // gdigrab's `title=` form rejects blank titles (Jewel-in-IDE tool windows etc.). The
        // documented fallback is region capture; AutoRecorder must apply it before reaching
        // FfmpegWindowRecorder so the require() in there never fires for routed callers.
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val winWindowRecorder =
            StubWindowRecorder(name = "ffmpegWindow", behavior = StubBehavior.ShouldNeverBeCalled)
        val window = StubTitledWindow(title = "")
        val recorder =
            autoRecorder(
                sckRecorder = sck,
                ffmpegRecorder = ffmpeg,
                windowsWindowRecorder = winWindowRecorder,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertTrue(
                ffmpeg.startCalled,
                "Region path should be the documented blank-title fallback",
            )
            assertEquals(0, winWindowRecorder.startCallCount)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `Windows host with null window title falls back to ffmpeg region capture`() {
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val winWindowRecorder =
            StubWindowRecorder(name = "ffmpegWindow", behavior = StubBehavior.ShouldNeverBeCalled)
        val window = StubTitledWindow(title = null)
        val recorder =
            autoRecorder(
                sckRecorder = sck,
                ffmpegRecorder = ffmpeg,
                windowsWindowRecorder = winWindowRecorder,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertTrue(ffmpeg.startCalled)
            assertEquals(0, winWindowRecorder.startCallCount)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `Windows host without a configured windowsWindowRecorder falls back to ffmpeg region`() {
        // Defensive case: even if the AutoRecorder construction couldn't resolve a Windows
        // window recorder (e.g. ffmpeg not on PATH at AutoRecorder() time), the router stays
        // usable for the region path that doesn't depend on the absent recorder.
        val sck = StubWindowRecorder(name = "sck", behavior = StubBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow(title = "MyApp")
        val recorder =
            autoRecorder(
                sckRecorder = sck,
                ffmpegRecorder = ffmpeg,
                windowsWindowRecorder = null,
                isMacOs = { false },
                isWindows = { true },
            )
        val output = tempMov()
        try {
            recorder.start(window = window, region = Rectangle(0, 0, 100, 100), output = output)

            assertTrue(ffmpeg.startCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    private fun tempMov(): Path = Files.createTempFile("spectre-auto-recorder-test-", ".mov")
}

// AutoRecorder's internal constructor takes the dispatch lambdas + the optional Windows window
// recorder. Tests use this helper so the public 3-arg constructor (which detect the host OS at
// runtime) doesn't leak through and make the suite host-dependent.
private fun autoRecorder(
    sckRecorder: WindowRecorder,
    ffmpegRecorder: Recorder,
    windowsWindowRecorder: WindowRecorder? = null,
    isMacOs: () -> Boolean,
    isWindows: () -> Boolean = { false },
): AutoRecorder =
    AutoRecorder(sckRecorder, ffmpegRecorder, windowsWindowRecorder, isMacOs, isWindows)

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

private class StubFfmpegRecorder : Recorder {
    var startCalled: Boolean = false
        private set

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        startCalled = true
        return NoopHandle(output)
    }
}

private class StubTitledWindow(title: String? = "MyApp") : TitledWindow {
    override var title: String? = title
}

private class NoopHandle(override val output: Path) : RecordingHandle {
    override val isStopped: Boolean = false

    override fun stop() = Unit
}
