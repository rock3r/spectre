package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingOptions
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScreenCaptureKitRecorderTest {

    private fun newRecorder(
        helperPath: Path = Path.of("/tmp/spectre-screencapture-fake"),
        process: Process = FakeHelperProcess(),
    ): Pair<ScreenCaptureKitRecorder, RecordingProcessFactory> {
        val factory = RecordingProcessFactory(process)
        val extractor =
            HelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(byteArrayOf(0x01)) },
                targetDirProvider = { helperPath.parent ?: Path.of("/tmp") },
            )
        return ScreenCaptureKitRecorder(helperExtractor = extractor, processFactory = factory) to
            factory
    }

    @Test
    fun `start spawns the helper with arguments derived from the call`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, factory) = newRecorder()
        try {
            recorder.start(
                window = window,
                windowOwnerPid = 4242L,
                output = output,
                options = RecordingOptions(frameRate = 30, captureCursor = true),
            )

            val argv = factory.lastArgv
            assertTrue(argv.isNotEmpty())
            assertTrue("--pid" in argv)
            assertEquals("4242", argv[argv.indexOf("--pid") + 1])
            assertTrue("--title-contains" in argv)
            // The discriminator value lives on the window's title at this point — assert the
            // recorder picked the same string for both.
            val discriminator = argv[argv.indexOf("--title-contains") + 1]
            assertTrue(window.title!!.contains(discriminator))
            assertEquals(output.toString(), argv.last())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start applies title discriminator and stop restores the original title`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder()
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            assertTrue(window.title!!.startsWith("MyApp Spectre/"))
            handle.stop()
            assertEquals("MyApp", window.title)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start surfaces helper exit-3 (window not found) with the documented message`() {
        // Helper exits 3 within the startup probe window when the discriminator never
        // matches. Recorder must surface this distinctly so callers can map it to "the
        // target window wasn't visible — recording did not start".
        val process = InstantlyExitingFakeProcess(exitCode = 3)
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder(process = process)
        try {
            val ex =
                assertFailsWith<IllegalStateException> {
                    recorder.start(window, 4242L, output, RecordingOptions())
                }
            assertTrue(
                ex.message?.contains("window") == true,
                "Error must mention the missing window so callers know what failed; got: ${ex.message}",
            )
            // Title must be restored even when start fails.
            assertEquals("MyApp", window.title)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start surfaces helper exit-4 (TCC denied) with permission guidance`() {
        val process = InstantlyExitingFakeProcess(exitCode = 4)
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder(process = process)
        try {
            val ex =
                assertFailsWith<IllegalStateException> {
                    recorder.start(window, 4242L, output, RecordingOptions())
                }
            assertTrue(
                ex.message?.contains("Screen Recording") == true ||
                    ex.message?.contains("permission", ignoreCase = true) == true,
                "Error must point at the TCC permission so callers can act; got: ${ex.message}",
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop sends q on stdin and waits for the helper`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val process = FakeHelperProcess()
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder(process = process)
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            assertFalse(handle.isStopped)

            handle.stop()

            assertTrue(handle.isStopped)
            assertEquals("q", process.stdinSentText, "Should signal helper by writing 'q' to stdin")
            assertTrue(process.waitForCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop is idempotent`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val process = FakeHelperProcess()
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder(process = process)
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            handle.stop()
            handle.stop()
            assertEquals("q", process.stdinSentText, "Stop should send 'q' exactly once")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `close delegates to stop`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder()
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            handle.use { /* AutoCloseable scope */ }
            assertTrue(handle.isStopped)
            assertEquals("MyApp", window.title)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop throws and still restores title if helper crashed mid-recording`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val process = CrashableFakeHelperProcess()
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder(process = process)
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            process.crash(exitCode = 5)
            assertFailsWith<IllegalStateException> { handle.stop() }
            // Title restoration is part of the cleanup contract — must happen even on the
            // failure path so the user's app isn't left with a bogus suffix forever.
            assertEquals("MyApp", window.title)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start defaults windowOwnerPid to the current JVM process`() {
        // Real callers usually want the recorder to find a window owned by the same JVM.
        // The default pid must reflect that without forcing every caller to pass it.
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, factory) = newRecorder()
        try {
            recorder.start(window = window, output = output, options = RecordingOptions())
            assertEquals(
                ProcessHandle.current().pid().toString(),
                factory.lastArgv[factory.lastArgv.indexOf("--pid") + 1],
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start preserves a null original title and stop restores it as null`() {
        val window = FakeTitledWindow(initialTitle = null)
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, _) = newRecorder()
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            handle.stop()
            assertNull(window.title)
        } finally {
            output.deleteIfExists()
        }
    }
}

private class RecordingProcessFactory(private val process: Process) :
    ScreenCaptureKitRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        return process
    }
}

/** Helper that stays alive until stdin closes — mirrors the Swift helper's q-on-stdin shape. */
private open class FakeHelperProcess : Process() {
    private val stdinBuffer = ByteArrayOutputStream()
    @Volatile protected var alive: Boolean = true
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

    // Emit the READY marker the recorder waits for so start() proceeds without timing out.
    // Real helper writes this once SCK + AVAssetWriter are running; tests don't need anything
    // after the marker.
    override fun getInputStream(): InputStream = ByteArrayInputStream("READY\n".toByteArray())

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

    override fun exitValue(): Int =
        if (alive) throw IllegalThreadStateException("still running") else 0

    override fun destroy() {
        alive = false
    }

    override fun isAlive(): Boolean = alive

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private class CrashableFakeHelperProcess : FakeHelperProcess() {
    private var exit: Int = 0

    fun crash(exitCode: Int) {
        alive = false
        exit = exitCode
    }

    override fun exitValue(): Int =
        if (alive) throw IllegalThreadStateException("still running") else exit

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = !alive
}

private class InstantlyExitingFakeProcess(private val exitCode: Int) : Process() {

    override fun getOutputStream(): OutputStream = ByteArrayOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exitCode

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = exitCode

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}
