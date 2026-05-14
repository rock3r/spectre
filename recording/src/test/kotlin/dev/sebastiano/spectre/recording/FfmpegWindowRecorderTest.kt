package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.ProcessHandle
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FfmpegWindowRecorderTest {

    @Test
    fun `start spawns ffmpeg with gdigrab title argv`() {
        val factory = WindowRecordingProcessFactory()
        val recorder =
            FfmpegWindowRecorder(ffmpegPath = FfmpegRecorder.PROBE_PATH, processFactory = factory)
        val window = WindowFakeTitledWindow(title = "Spectre Sample")
        val output = Files.createTempFile("spectre-window-recorder-test-", ".mp4")
        try {
            recorder.start(
                window = window,
                windowOwnerPid = ProcessHandle.current().pid(),
                output = output,
                options = RecordingOptions(),
            )

            val argv = factory.lastArgv
            assertTrue(argv.isNotEmpty(), "Process factory should have been invoked")
            assertTrue("gdigrab" in argv, "Argv should select gdigrab: $argv")
            assertContainsSequence(argv, listOf("-i", "title=Spectre Sample"))
            assertEquals(output.toString(), argv.last(), "Argv should end with the output path")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start rejects blank window title`() {
        // gdigrab's title= form treats an empty title as the desktop, which would silently
        // record the wrong surface. AutoRecorder is supposed to filter blank titles before
        // they reach this recorder; the require() here is the defence-in-depth.
        val factory = WindowRecordingProcessFactory()
        val recorder =
            FfmpegWindowRecorder(ffmpegPath = FfmpegRecorder.PROBE_PATH, processFactory = factory)
        val output = Files.createTempFile("spectre-window-recorder-test-", ".mp4")
        try {
            assertFailsWith<IllegalArgumentException> {
                recorder.start(
                    window = WindowFakeTitledWindow(title = ""),
                    windowOwnerPid = 0,
                    output = output,
                    options = RecordingOptions(),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                recorder.start(
                    window = WindowFakeTitledWindow(title = null),
                    windowOwnerPid = 0,
                    output = output,
                    options = RecordingOptions(),
                )
            }
            assertFailsWith<IllegalArgumentException> {
                recorder.start(
                    window = WindowFakeTitledWindow(title = "   "),
                    windowOwnerPid = 0,
                    output = output,
                    options = RecordingOptions(),
                )
            }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start creates the output parent directory`() {
        val factory = WindowRecordingProcessFactory()
        val recorder =
            FfmpegWindowRecorder(ffmpegPath = FfmpegRecorder.PROBE_PATH, processFactory = factory)
        val baseDir = Files.createTempDirectory("spectre-window-recorder-test-")
        val nested = baseDir.resolve("nested/subdir/output.mp4")
        try {
            recorder.start(
                window = WindowFakeTitledWindow(title = "MyApp"),
                windowOwnerPid = 0,
                output = nested,
                options = RecordingOptions(),
            )
            assertTrue(Files.isDirectory(nested.parent), "Parent directory must be created")
        } finally {
            nested.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stop sends q on stdin and waits for the process`() {
        // Lifecycle parity with FfmpegRecorder: same shared spawnFfmpegRecording() helper, so
        // the q-on-stdin shutdown signal applies the same way to a window-targeted recording.
        val process = WindowFakeProcess()
        val recorder =
            FfmpegWindowRecorder(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = WindowProcessFactoryReturning(process),
            )
        val output = Files.createTempFile("spectre-window-recorder-test-", ".mp4")
        try {
            val handle =
                recorder.start(
                    window = WindowFakeTitledWindow(title = "MyApp"),
                    windowOwnerPid = 0,
                    output = output,
                    options = RecordingOptions(),
                )
            handle.stop()

            assertTrue(handle.isStopped)
            assertEquals("q", process.stdinSentText, "Should signal ffmpeg by writing 'q' to stdin")
            assertTrue(process.waitForCalled, "Should wait for the process to exit cleanly")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start throws when the spawned ffmpeg exits immediately`() {
        // Common Windows immediate-exit modes: gdigrab title= matches no visible window, or
        // gdigrab itself isn't available because the host isn't actually Windows. The startup
        // probe must surface that, not return a handle that produces an empty file.
        val recorder =
            FfmpegWindowRecorder(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = WindowProcessFactoryReturning(InstantlyExitingWindowFakeProcess()),
            )
        val output = Files.createTempFile("spectre-window-recorder-test-", ".mp4")
        try {
            assertFailsWith<IllegalStateException> {
                    recorder.start(
                        window = WindowFakeTitledWindow(title = "MyApp"),
                        windowOwnerPid = 0,
                        output = output,
                        options = RecordingOptions(),
                    )
                }
                .also { assertTrue(it.message?.contains("exited immediately") == true) }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `constructor with non-executable explicit path throws`() {
        val nonexistent = Path.of("/this/path/does/not/exist/ffmpeg")
        assertFailsWith<IllegalArgumentException> { FfmpegWindowRecorder(ffmpegPath = nonexistent) }
    }
}

private class WindowFakeTitledWindow(title: String?) : TitledWindow {
    override var title: String? = title
    override val bounds: java.awt.Rectangle = java.awt.Rectangle(0, 0, 100, 100)
}

private class WindowRecordingProcessFactory : FfmpegRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        return WindowFakeProcess()
    }
}

private class WindowProcessFactoryReturning(private val process: Process) :
    FfmpegRecorder.ProcessFactory {
    override fun start(argv: List<String>): Process = process
}

/**
 * Minimal in-memory `Process` stand-in mirroring the shape used by [FfmpegRecorderTest]'s fakes.
 * Only models the surface [spawnFfmpegRecording] and [RecordingHandle] use.
 */
private class WindowFakeProcess : Process() {
    private val stdinBuffer = ByteArrayOutputStream()
    private var alive = true
    var waitForCalled: Boolean = false
        private set

    val stdinSentText: String
        get() = stdinBuffer.toString(Charsets.UTF_8)

    override fun getOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                stdinBuffer.write(b)
            }

            override fun close() {
                alive = false
            }
        }

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        waitForCalled = true
        alive = false
        return 0
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitForCalled = true
        return !alive
    }

    override fun exitValue(): Int = 0

    override fun destroy() {
        alive = false
    }

    override fun isAlive(): Boolean = alive

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private class InstantlyExitingWindowFakeProcess : Process() {

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = EXIT_CODE

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = EXIT_CODE

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)

    private companion object {
        const val EXIT_CODE: Int = 1
    }
}

private fun assertContainsSequence(argv: List<String>, expected: List<String>) {
    val matched = argv.windowed(expected.size).any { it == expected }
    check(matched) { "Expected $expected to appear contiguously in $argv" }
}
