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
        val sck = StubWindowRecorder(behavior = SckBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val recorder = AutoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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
    fun `non-mac host always routes to ffmpeg even with a window`() {
        val sck = StubWindowRecorder(behavior = SckBehavior.ShouldNeverBeCalled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow()
        val recorder = AutoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { false })
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
        val sck = StubWindowRecorder(behavior = SckBehavior.Succeeds)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow()
        val recorder = AutoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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
        val sck = StubWindowRecorder(behavior = SckBehavior.HelperNotBundled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow()
        val recorder = AutoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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
        val sck = StubWindowRecorder(behavior = SckBehavior.RuntimeFailure)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow()
        val recorder = AutoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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
        val sck = StubWindowRecorder(behavior = SckBehavior.HelperNotBundled)
        val ffmpeg = StubFfmpegRecorder()
        val window = StubTitledWindow()
        val recorder = AutoRecorder(sckRecorder = sck, ffmpegRecorder = ffmpeg, isMacOs = { true })
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

    private fun tempMov(): Path = Files.createTempFile("spectre-auto-recorder-test-", ".mov")
}

private enum class SckBehavior {
    Succeeds,
    HelperNotBundled,
    RuntimeFailure,
    ShouldNeverBeCalled,
}

private class StubWindowRecorder(private val behavior: SckBehavior) : WindowRecorder {
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
            SckBehavior.Succeeds -> NoopHandle(output)
            SckBehavior.HelperNotBundled ->
                throw HelperNotBundledException("test: helper not bundled")
            SckBehavior.RuntimeFailure -> throw IllegalStateException("test: TCC denied")
            SckBehavior.ShouldNeverBeCalled ->
                error("SCK was not supposed to be invoked in this test")
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

private class StubTitledWindow : TitledWindow {
    override var title: String? = "MyApp"
}

private class NoopHandle(override val output: Path) : RecordingHandle {
    override val isStopped: Boolean = false

    override fun stop() = Unit
}
