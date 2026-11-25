package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.stopWithResult
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenCaptureKitRecorderTest {

    private fun newRecorder(
        helperPath: Path = Path.of("/tmp/spectre-screencapture-fake"),
        process: Process = FakeHelperProcess(),
        writesOutput: Boolean = true,
    ): Pair<ScreenCaptureKitRecorder, RecordingProcessFactory> {
        val factory = RecordingProcessFactory(process, writesOutput)
        val extractor =
            HelperBinaryExtractor(
                envLookup = { null },
                sysPropLookup = { null },
                materialLocator = {
                    helperAppBundleMaterial(
                        executable = byteArrayOf(0x01),
                        infoPlist = byteArrayOf(),
                    )
                },
                targetDirProvider = { helperPath.parent ?: Path.of("/tmp") },
            )
        return ScreenCaptureKitRecorder(
            helperExtractor = extractor,
            processFactory = factory,
            requireScreenCaptureAccess = {},
        ) to factory
    }

    @Test
    fun `direct recording uses the AWT frame for a shared title with or without a crop`() {
        withSharedSckFrames { left, _ ->
            val output = Files.createTempFile("spectre-direct-", ".mov")
            val pid = ProcessHandle.current().pid()
            try {
                for (cropped in listOf(false, true)) {
                    val (recorder, factory) = newRecorder()
                    val target = left.asTitledWindow()
                    val options = RecordingOptions()
                    if (cropped) {
                            recorder.startCropped(
                                target,
                                Rectangle(2, 3, 20, 30),
                                pid,
                                output,
                                options,
                            )
                        } else {
                            recorder.start(target, pid, output, options)
                        }
                        .use {
                            val argv = factory.lastArgv
                            assertFalse("--title-contains" in argv)
                            assertEquals("10,20,300,200", argv[argv.indexOf("--frame") + 1])
                            assertEquals(pid.toString(), argv[argv.indexOf("--pid") + 1])
                        }
                }
            } finally {
                output.deleteIfExists()
            }
        }
    }

    @Test
    fun `final result distinguishes reported drops from unreported drops`() {
        val output = Files.createTempFile("spectre-result-", ".mov")
        try {
            for (count in listOf(null, 0L, 7L)) {
                val process =
                    object : FakeHelperProcess() {
                        override fun getInputStream(): InputStream =
                            ("READY\n" + count?.let { "RECORDING_RESULT $it true\n" }.orEmpty())
                                .byteInputStream()
                    }
                val (recorder, _) = newRecorder(process = process)
                val handle =
                    recorder.startMatchingTitle(
                        "App",
                        42L,
                        output,
                        RecordingOptions(),
                        crop = Rectangle(0, 0, 4, 4),
                    )
                val result = handle.stopWithResult()
                assertEquals(count, result.droppedFrames)
                assertEquals(true, result.cropFixedAtStart)
                assertEquals(result, handle.stopWithResult())
            }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `clean helper exit cannot claim an unchanged pre-existing movie`() {
        val output = Files.createTempFile("spectre-sck-stale-", ".mov")
        try {
            Files.write(output, byteArrayOf(1))
            val (recorder, _) = newRecorder(writesOutput = false)
            val handle = recorder.start(FakeTitledWindow("App"), 42L, output, RecordingOptions())
            assertFailsWith<IllegalStateException> { handle.stop() }
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start spawns the helper with arguments derived from the call`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
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
            assertEquals("MyApp", argv[argv.indexOf("--title-contains") + 1])
            assertEquals("MyApp", window.title)
            assertEquals(output.toString(), argv.last())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start selects a shared-title window by its owner-scoped frame`() {
        val window =
            FramedWindow(
                logicalFrame = Rectangle(-20, 30, 400, 250),
                deviceFrame = Rectangle(-40, 60, 800, 500),
            )
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        val (recorder, factory) = newRecorder()
        try {
            recorder.start(window, 4242L, output, RecordingOptions())

            assertContainsSequence(factory.lastArgv, listOf("--pid", "4242"))
            assertContainsSequence(factory.lastArgv, listOf("--frame", "-20,30,400,250"))
            assertTrue("--title-contains" !in factory.lastArgv)
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
        Files.write(output, byteArrayOf(1))
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
    fun `start with region surfaces helper exit-3 as missing display`() {
        val process = InstantlyExitingFakeProcess(exitCode = 3)
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, _) = newRecorder(process = process)
        try {
            val ex =
                assertFailsWith<IllegalStateException> {
                    recorder.start(
                        region = Rectangle(10, 20, 320, 180),
                        output = output,
                        options = RecordingOptions(screenIndex = 5),
                    )
                }

            assertTrue(
                ex.message?.contains("display") == true,
                "Error must mention the missing display for region capture; got: ${ex.message}",
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start surfaces helper exit-4 (TCC denied) with permission guidance`() {
        val process = InstantlyExitingFakeProcess(exitCode = 4)
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
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
        Files.write(output, byteArrayOf(1))
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
        Files.write(output, byteArrayOf(1))
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
        Files.write(output, byteArrayOf(1))
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
        Files.write(output, byteArrayOf(1))
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
        Files.write(output, byteArrayOf(1))
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
    fun `start with region spawns the helper with region source arguments`() {
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            recorder.start(
                region = Rectangle(10, 20, 320, 180),
                output = output,
                options = RecordingOptions(frameRate = 24, captureCursor = false, screenIndex = 1),
            )

            val argv = factory.lastArgv
            assertTrue(argv.isNotEmpty())
            assertContainsSequence(argv, listOf("--source", "region"))
            assertContainsSequence(argv, listOf("--region", "10,20,320,180"))
            assertContainsSequence(argv, listOf("--display-index", "1"))
            assertContainsSequence(argv, listOf("--fps", "24"))
            assertContainsSequence(argv, listOf("--cursor", "false"))
            assertEquals(output.toString(), argv.last())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start with region rejects custom codec before spawning helper`() {
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    recorder.start(
                        region = Rectangle(10, 20, 320, 180),
                        output = output,
                        options = RecordingOptions(codec = "hevc"),
                    )
                }

            assertTrue(error.message.orEmpty().contains("custom RecordingOptions.codec"))
            assertTrue(factory.lastArgv.isEmpty())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start with window rejects custom codec before mutating the title`() {
        val window = FakeTitledWindow(initialTitle = "MyApp")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    recorder.start(
                        window = window,
                        windowOwnerPid = 4242L,
                        output = output,
                        options = RecordingOptions(codec = "hevc"),
                    )
                }

            assertTrue(error.message.orEmpty().contains("custom RecordingOptions.codec"))
            assertEquals("MyApp", window.title)
            assertTrue(factory.lastArgv.isEmpty())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start with region rejects negative origins before spawning helper`() {
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    recorder.start(
                        region = Rectangle(-1, 20, 320, 180),
                        output = output,
                        options = RecordingOptions(),
                    )
                }

            assertTrue(error.message.orEmpty().contains("origin"))
            assertTrue(factory.lastArgv.isEmpty())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start with region rejects empty regions before spawning helper`() {
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            val error =
                assertFailsWith<IllegalArgumentException> {
                    recorder.start(
                        region = Rectangle(10, 20, 0, 180),
                        output = output,
                        options = RecordingOptions(),
                    )
                }

            assertTrue(error.message.orEmpty().contains("non-empty region"))
            assertTrue(factory.lastArgv.isEmpty())
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start does not mutate a uniquely titled window and matches its original title`() {
        val window = DiscriminationAwareFakeWindow("Unique title")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            val handle = recorder.start(window, 4242L, output, RecordingOptions())
            assertEquals("Unique title", window.title)
            assertContainsSequence(factory.lastArgv, listOf("--title-contains", "Unique title"))
            handle.stop()
            assertEquals("Unique title", window.title)
            assertEquals(0, window.titleWrites, "Recorder must never write a unique window title")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `startCropped does not mutate a uniquely titled window`() {
        val window = DiscriminationAwareFakeWindow("Unique title")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, factory) = newRecorder()
        try {
            val handle =
                recorder.startCropped(
                    window = window,
                    cropInWindow = Rectangle(1, 2, 30, 40),
                    windowOwnerPid = 4242L,
                    output = output,
                    options = RecordingOptions(),
                )
            assertContainsSequence(factory.lastArgv, listOf("--title-contains", "Unique title"))
            handle.stop()
            assertEquals(0, window.titleWrites, "Recorder must never write a unique window title")
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start with a unique title does not touch the title when helper start fails`() {
        val window = DiscriminationAwareFakeWindow("Unique title")
        val output = Files.createTempFile("spectre-sck-test-", ".mov")
        Files.write(output, byteArrayOf(1))
        val (recorder, _) = newRecorder(process = InstantlyExitingFakeProcess(exitCode = 3))
        try {
            assertFailsWith<IllegalStateException> {
                recorder.start(window, 4242L, output, RecordingOptions())
            }
            assertEquals(0, window.titleWrites, "Recorder must never write a unique window title")
        } finally {
            output.deleteIfExists()
        }
    }
}

/** Fake window that reports whether its title is unique, and counts title writes. */
private class DiscriminationAwareFakeWindow(initialTitle: String?) : TitledWindow {
    var titleWrites: Int = 0
        private set

    override var title: String? = initialTitle
        set(value) {
            titleWrites += 1
            field = value
        }

    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class RecordingProcessFactory(
    private val process: Process,
    private val writesOutput: Boolean,
) : ScreenCaptureKitRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        if (writesOutput) Files.write(Path.of(argv.last()), byteArrayOf(1, 2))
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

private fun assertContainsSequence(actual: List<String>, expected: List<String>) {
    val window =
        actual.windowed(size = expected.size, step = 1, partialWindows = false).firstOrNull {
            it == expected
        }
    assertTrue(window != null, "Expected argv to contain $expected, got $actual")
}
