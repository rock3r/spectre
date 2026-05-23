package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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

class WindowsGraphicsCaptureRecorderTest {

    @Test
    fun `start spawns helper and waits for ready marker`() {
        val helper = Files.createTempFile("spectre-window-capture", ".exe")
        val output = Files.createTempFile("spectre-wgc-test-", ".mp4")
        val factory = RecordingProcessFactory(FakeRecordingProcess())
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { null },
                        targetDirProvider = { helper.parent },
                        getenv = { helper.toString() },
                        osArch = { "amd64" },
                    ),
                processFactory = factory,
            )
        try {
            val handle =
                recorder.start(
                    window = Window(title = "Spectre"),
                    windowOwnerPid = 4242,
                    output = output,
                    options = RecordingOptions(frameRate = 60, captureCursor = false),
                )

            assertFalse(handle.isStopped)
            assertEquals(
                listOf(
                    helper.toString(),
                    "--mode",
                    "recording",
                    "--source",
                    "window",
                    "--title",
                    "Spectre",
                    "--owner-pid",
                    "4242",
                    "--fps",
                    "60",
                    "--cursor",
                    "false",
                    "--output",
                    output.toString(),
                ),
                factory.lastArgv,
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start region spawns helper with region source and rectangle`() {
        val helper = Files.createTempFile("spectre-window-capture", ".exe")
        val output = Files.createTempFile("spectre-wgc-region-test-", ".mp4")
        val factory = RecordingProcessFactory(FakeRecordingProcess())
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { null },
                        targetDirProvider = { helper.parent },
                        getenv = { helper.toString() },
                        osArch = { "amd64" },
                    ),
                processFactory = factory,
            )
        try {
            val region = Rectangle(10, 20, 300, 200)

            recorder.start(
                region = region,
                output = output,
                options = RecordingOptions(frameRate = 24, captureCursor = true),
            )

            assertEquals(
                listOf(
                    helper.toString(),
                    "--mode",
                    "recording",
                    "--source",
                    "region",
                    "--x",
                    "10",
                    "--y",
                    "20",
                    "--width",
                    "300",
                    "--height",
                    "200",
                    "--fps",
                    "24",
                    "--cursor",
                    "true",
                    "--output",
                    output.toString(),
                ),
                factory.lastArgv,
            )
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `start region rejects empty rectangles before helper extraction`() {
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { error("should not extract") },
                        targetDirProvider = { Path.of(".") },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(FakeRecordingProcess()),
            )

        assertFailsWith<IllegalArgumentException> {
            recorder.start(Rectangle(0, 0, 0, 100), Path.of("out.mp4"), RecordingOptions())
        }
    }

    @Test
    fun `start rejects unsupported codec before helper extraction`() {
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { error("should not extract") },
                        targetDirProvider = { Path.of(".") },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(FakeRecordingProcess()),
            )

        assertFailsWith<IllegalArgumentException> {
            recorder.start(
                Window(title = "Spectre"),
                1,
                Path.of("out.mp4"),
                RecordingOptions(codec = "libx264rgb"),
            )
        }
    }

    @Test
    fun `start rejects unsupported screen index before helper extraction`() {
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { error("should not extract") },
                        targetDirProvider = { Path.of(".") },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(FakeRecordingProcess()),
            )

        assertFailsWith<IllegalArgumentException> {
            recorder.start(
                Rectangle(0, 0, 100, 100),
                Path.of("out.mp4"),
                RecordingOptions(screenIndex = 1),
            )
        }
    }

    @Test
    fun `start rejects blank titles before helper extraction`() {
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { error("should not extract") },
                        targetDirProvider = { Path.of(".") },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(FakeRecordingProcess()),
            )

        assertFailsWith<IllegalArgumentException> {
            recorder.start(Window(title = ""), 1, Path.of("out.mp4"), RecordingOptions())
        }
    }

    @Test
    fun `start reports helper window lookup miss`() {
        val helper = Files.createTempFile("spectre-window-capture", ".exe")
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { null },
                        targetDirProvider = { helper.parent },
                        getenv = { helper.toString() },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(InstantlyExitingProcess(exit = 3)),
            )

        val error =
            assertFailsWith<IllegalStateException> {
                recorder.start(
                    Window(title = "Missing"),
                    4242,
                    Path.of("out.mp4"),
                    RecordingOptions(),
                )
            }

        assertTrue(error.message.orEmpty().contains("could not find"))
    }

    @Test
    fun `stop writes q and waits for helper`() {
        val output = Files.createTempFile("spectre-wgc-test-", ".mp4")
        val process = FakeRecordingProcess()
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { ByteArrayInputStream(byteArrayOf(1)) },
                        targetDirProvider = { output.parent },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(process),
            )
        try {
            val handle = recorder.start(Window(title = "Spectre"), 4242, output, RecordingOptions())

            handle.stop()

            assertTrue(handle.isStopped)
            assertEquals("q", process.stdinSentText)
            assertTrue(process.waitForCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop succeeds when helper already exited cleanly before q write`() {
        val output = Files.createTempFile("spectre-wgc-test-", ".mp4")
        val process = AlreadyExitedProcess(exit = 0)
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { ByteArrayInputStream(byteArrayOf(1)) },
                        targetDirProvider = { output.parent },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(process),
            )
        try {
            val handle = recorder.start(Window(title = "Spectre"), 4242, output, RecordingOptions())

            handle.stop()

            assertTrue(handle.isStopped)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop treats self-issued destroy as expected`() {
        val output = Files.createTempFile("spectre-wgc-test-", ".mp4")
        val process = StubbornRecordingProcess(exitAfterDestroy = 1)
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { ByteArrayInputStream(byteArrayOf(1)) },
                        targetDirProvider = { output.parent },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(process),
            )
        try {
            val handle = recorder.start(Window(title = "Spectre"), 4242, output, RecordingOptions())

            handle.stop()

            assertTrue(handle.isStopped)
            assertTrue(process.destroyCalled)
        } finally {
            output.deleteIfExists()
        }
    }

    @Test
    fun `stop fails if helper remains alive after destroyForcibly`() {
        val output = Files.createTempFile("spectre-wgc-test-", ".mp4")
        val process = NeverExitingRecordingProcess()
        val recorder =
            WindowsGraphicsCaptureRecorder(
                helperExtractor =
                    WindowsGraphicsCaptureHelperBinaryExtractor(
                        resourceLocator = { ByteArrayInputStream(byteArrayOf(1)) },
                        targetDirProvider = { output.parent },
                        osArch = { "amd64" },
                    ),
                processFactory = RecordingProcessFactory(process),
            )
        try {
            val handle = recorder.start(Window(title = "Spectre"), 4242, output, RecordingOptions())

            val error = assertFailsWith<IllegalStateException> { handle.stop() }

            assertTrue(error.message.orEmpty().contains("destroyForcibly"))
        } finally {
            output.deleteIfExists()
        }
    }
}

private class Window(title: String?) : TitledWindow {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class RecordingProcessFactory(private val process: Process) :
    WindowsGraphicsCaptureRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        return process
    }
}

private open class FakeRecordingProcess : Process() {
    private val stdinBuffer = ByteArrayOutputStream()
    @Volatile protected var alive: Boolean = true
    var waitForCalled: Boolean = false
        protected set

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

private class InstantlyExitingProcess(private val exit: Int) : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = exit

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private class AlreadyExitedProcess(private val exit: Int) : FakeRecordingProcess() {
    init {
        alive = false
    }

    override fun getOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) {
                throw IOException("The pipe has been ended")
            }
        }

    override fun waitFor(): Int = exit

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = exit
}

private class StubbornRecordingProcess(private val exitAfterDestroy: Int) : FakeRecordingProcess() {
    var destroyCalled: Boolean = false
        private set

    override fun getOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = Unit
        }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitForCalled = true
        return !alive
    }

    override fun waitFor(): Int = exitAfterDestroy

    override fun exitValue(): Int = exitAfterDestroy

    override fun destroy() {
        destroyCalled = true
        alive = false
    }
}

private class NeverExitingRecordingProcess : FakeRecordingProcess() {
    override fun getOutputStream(): OutputStream =
        object : OutputStream() {
            override fun write(b: Int) = Unit
        }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
        waitForCalled = true
        return false
    }

    override fun destroy() = Unit

    override fun destroyForcibly(): Process = this

    override fun isAlive(): Boolean = true
}
