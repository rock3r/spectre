package dev.sebastiano.spectre.recording

import java.awt.Rectangle
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FfmpegRecorderTest {

    @Test
    fun `start spawns the subprocess with the avfoundation argv`() {
        val factory = RecordingProcessFactory()
        val recorder =
            FfmpegRecorder(ffmpegPath = FfmpegRecorder.PROBE_PATH, processFactory = factory)
        val output = Files.createTempFile("spectre-recording-test-", ".mp4")
        try {
            recorder.start(Rectangle(0, 0, 100, 100), output, RecordingOptions())

            val argv = factory.lastArgv
            assertTrue(argv.isNotEmpty(), "Process factory should have been invoked")
            assertTrue("avfoundation" in argv, "Argv should select avfoundation: $argv")
            assertEquals(output.toString(), argv.last(), "Argv should end with the output path")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start creates the output parent directory`() {
        val factory = RecordingProcessFactory()
        val recorder =
            FfmpegRecorder(ffmpegPath = FfmpegRecorder.PROBE_PATH, processFactory = factory)
        val baseDir = Files.createTempDirectory("spectre-recording-test-")
        val nested = baseDir.resolve("nested/subdir/output.mp4")
        try {
            recorder.start(Rectangle(0, 0, 50, 50), nested, RecordingOptions())
            assertTrue(Files.isDirectory(nested.parent), "Parent directory must be created")
        } finally {
            nested.parent.toFile().deleteRecursively()
        }
    }

    @Test
    fun `stop sends q on stdin and waits for the process`() {
        val process = FakeProcess()
        val factory = ProcessFactoryReturning(process)
        val recorder =
            FfmpegRecorder(ffmpegPath = FfmpegRecorder.PROBE_PATH, processFactory = factory)
        val output = Files.createTempFile("spectre-recording-test-", ".mp4")
        try {
            val handle = recorder.start(Rectangle(0, 0, 50, 50), output, RecordingOptions())
            assertFalse(handle.isStopped)

            handle.stop()

            assertTrue(handle.isStopped)
            assertEquals("q", process.stdinSentText, "Should signal ffmpeg by writing 'q' to stdin")
            assertTrue(process.waitForCalled, "Should wait for the process to exit cleanly")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop is idempotent`() {
        val process = FakeProcess()
        val recorder =
            FfmpegRecorder(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = ProcessFactoryReturning(process),
            )
        val output = Files.createTempFile("spectre-recording-test-", ".mp4")
        try {
            val handle = recorder.start(Rectangle(0, 0, 10, 10), output, RecordingOptions())
            handle.stop()
            handle.stop() // second call must not throw or send a second 'q'
            assertEquals("q", process.stdinSentText, "Stop should send 'q' exactly once")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `close delegates to stop`() {
        val process = FakeProcess()
        val recorder =
            FfmpegRecorder(
                ffmpegPath = FfmpegRecorder.PROBE_PATH,
                processFactory = ProcessFactoryReturning(process),
            )
        val output = Files.createTempFile("spectre-recording-test-", ".mp4")
        try {
            val handle = recorder.start(Rectangle(0, 0, 10, 10), output, RecordingOptions())
            handle.use { /* AutoCloseable scope */ }
            assertTrue(handle.isStopped)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `resolveFfmpegPath returns PROBE_PATH when ffmpeg is not on PATH`() {
        // We can't reliably test the positive path in CI (ffmpeg may or may not be installed),
        // but the negative-path sentinel is observable: PROBE_PATH equals Paths.get("ffmpeg").
        // resolveFfmpegPath() returns either an absolute resolved path *or* PROBE_PATH.
        val resolved = FfmpegRecorder.resolveFfmpegPath()
        // Either the resolved path is absolute (we found ffmpeg) or it equals the sentinel.
        assertTrue(
            resolved.isAbsolute || resolved == FfmpegRecorder.PROBE_PATH,
            "resolveFfmpegPath should return an absolute path or PROBE_PATH, got $resolved",
        )
    }

    @Test
    fun `constructor with non-executable explicit path throws`() {
        val nonexistent = Path.of("/this/path/does/not/exist/ffmpeg")
        kotlin.test.assertFailsWith<IllegalArgumentException> {
            FfmpegRecorder(ffmpegPath = nonexistent)
        }
    }
}

private class RecordingProcessFactory : FfmpegRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        return FakeProcess()
    }
}

private class ProcessFactoryReturning(private val process: Process) :
    FfmpegRecorder.ProcessFactory {
    override fun start(argv: List<String>): Process = process
}

/**
 * Minimal in-memory `Process` stand-in. Only models the surface FfmpegRecorder uses: stdin,
 * isAlive, waitFor, destroy / destroyForcibly. The real ffmpeg subprocess shape is huge, but none
 * of the rest is touched by the recorder lifecycle.
 */
private class FakeProcess : Process() {
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
                // Simulate ffmpeg exiting after the 'q' on stdin closes its input.
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
        alive = false
        return true
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
