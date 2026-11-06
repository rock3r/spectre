package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * #612: a window still used to start a new helper process, and a new Windows Graphics Capture
 * session, for every sample. The screenshotter now keeps one `--mode stills` helper per target and
 * asks it for each still.
 */
class WindowsStillSessionsTest {

    @Test
    fun `repeated stills of one window reuse one helper process`() {
        val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame(), frame())) }
        val screenshotter = screenshotter(factory)

        val first = screenshotter.captureWindow(StillWindow("Spectre Sample"), 4242L)
        val second = screenshotter.captureWindow(StillWindow("Spectre Sample"), 4242L)

        assertEquals(1, factory.started.size)
        val argv = factory.started.single()
        assertContainsSequence(argv, listOf("--mode", "stills", "--source", "window"))
        assertContainsSequence(argv, listOf("--title", "Spectre Sample"))
        assertContainsSequence(argv, listOf("--owner-pid", "4242"))
        assertEquals(2, first.width)
        assertEquals(1, first.height)
        assertEquals(0xFF030201.toInt(), first.getRGB(0, 0))
        assertEquals(first.getRGB(1, 0), second.getRGB(1, 0))
    }

    @Test
    fun `partly transparent pixels are converted from premultiplied alpha`() {
        val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame())) }

        val image = screenshotter(factory).captureWindow(StillWindow("Spectre Sample"), 4242L)

        // Premultiplied BGRA 0x10,0x20,0x30 at alpha 0x55 (one third) is R=0x90 G=0x60 B=0x30.
        assertEquals(0x55906030, image.getRGB(1, 0))
    }

    @Test
    fun `an untitled window is selected by its device-pixel frame`() {
        val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame())) }
        val window =
            FramedWindow(
                logicalFrame = Rectangle(-1280, 40, 300, 200),
                deviceFrame = Rectangle(-1280, 60, 450, 300),
            )

        screenshotter(factory).captureWindow(window, 4242L)

        val argv = factory.started.single()
        assertContainsSequence(
            argv,
            listOf(
                "--frame-x",
                "-1280",
                "--frame-y",
                "60",
                "--frame-width",
                "450",
                "--frame-height",
                "300",
            ),
        )
        assertTrue("--title" !in argv && "--output" !in argv, "argv: $argv")
    }

    @Test
    fun `different windows get their own helper`() {
        val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame())) }
        val screenshotter = screenshotter(factory)

        screenshotter.captureWindow(StillWindow("First"), 4242L)
        screenshotter.captureWindow(StillWindow("Second"), 4242L)

        assertEquals(2, factory.started.size)
    }

    @Test
    fun `a stale helper is replaced by a new one`() {
        val helpers =
            ArrayDeque(
                listOf(
                    StillsHelper(frames = listOf(STALE)),
                    StillsHelper(frames = listOf(frame())),
                )
            )
        val factory = StillsProcessFactory { helpers.removeFirst() }

        val image = screenshotter(factory).captureWindow(StillWindow("Spectre Sample"), 4242L)

        assertEquals(2, factory.started.size)
        assertEquals(2, image.width)
    }

    @Test
    fun `an idle helper is told to quit`() {
        val helper = StillsHelper(frames = listOf(frame()))
        val factory = StillsProcessFactory { helper }

        screenshotter(factory, idleCloseMillis = 50).captureWindow(StillWindow("Spectre"), 4242L)

        assertTrue(helper.quit.await(5, TimeUnit.SECONDS), "the idle helper was not closed")
    }

    @Test
    fun `a helper that cannot find the window reports the lookup miss`() {
        val factory = StillsProcessFactory { StillsHelper(exitBeforeReady = EXIT_WINDOW_NOT_FOUND) }

        val error =
            assertFailsWith<IllegalStateException> {
                screenshotter(factory).captureWindow(StillWindow("Missing"), 4242L)
            }

        assertContains(error.message.orEmpty(), "could not find")
    }

    @Test
    fun `a helper that never becomes ready is aborted`() {
        val helper = StillsHelper(neverReady = true)
        val factory = StillsProcessFactory { helper }

        val error =
            assertFailsWith<IllegalStateException> {
                screenshotter(factory, readyTimeoutMillis = 100)
                    .captureWindow(StillWindow("Stuck"), 4242L)
            }

        assertContains(error.message.orEmpty(), "Timed out")
        assertTrue(helper.destroyed)
    }

    private fun screenshotter(
        factory: StillsProcessFactory,
        idleCloseMillis: Long = 60_000,
        readyTimeoutMillis: Long = 5_000,
    ): WindowsWindowScreenshotter {
        val helper = Files.createTempFile("spectre-helper", ".exe")
        return WindowsWindowScreenshotter(
            helperExtractor =
                WindowsGraphicsCaptureHelperBinaryExtractor(
                    resourceLocator = { null },
                    targetDirProvider = { helper.parent },
                    getenv = { helper.toString() },
                    osArch = { "amd64" },
                ),
            processFactory = factory,
            stillSessions =
                WindowsStillSessions(
                    idleCloseMillis = idleCloseMillis,
                    readyTimeoutMillis = readyTimeoutMillis,
                    frameTimeoutMillis = 5_000,
                ),
        )
    }
}

private val STALE: ByteArray = "STALE window changed\n".toByteArray(Charsets.US_ASCII)

/** A 2x1 BGRA frame: an opaque pixel, then a partly transparent premultiplied one. */
private fun frame(): ByteArray =
    "FRAME 2 1\n".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x10, 0x20, 0x30, 0x55)

private class StillWindow(title: String) : TitledWindow {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class StillsProcessFactory(private val next: () -> StillsHelper) :
    WindowsWindowScreenshotter.ProcessFactory {
    val started: MutableList<List<String>> = mutableListOf()

    override fun start(argv: List<String>): Process {
        started += argv
        return next().also { it.run() }
    }
}

/** Speaks the `--mode stills` protocol: READY, then one queued answer per `c` command. */
private class StillsHelper(
    frames: List<ByteArray> = emptyList(),
    private val exitBeforeReady: Int? = null,
    private val neverReady: Boolean = false,
) : Process() {
    private val answers = ArrayDeque(frames)
    private val toHelper = PipedInputStream()
    private val fromJvm = PipedOutputStream(toHelper)
    private val toJvm = PipedOutputStream()
    private val fromHelper = PipedInputStream(toJvm, 1 shl 16)
    private val exited = CompletableFuture<Int>()
    val quit: CountDownLatch = CountDownLatch(1)

    @Volatile
    var destroyed: Boolean = false
        private set

    fun run() {
        Thread {
            if (exitBeforeReady != null) {
                toJvm.close()
                exited.complete(exitBeforeReady)
                return@Thread
            }
            if (neverReady) return@Thread
            toJvm.write("READY\n".toByteArray(Charsets.US_ASCII))
            toJvm.flush()
            val commands = toHelper.bufferedReader(Charsets.US_ASCII)
            while (true) {
                val line = runCatching { commands.readLine() }.getOrNull()
                if (line == null || line == "q") break
                if (line == "c") {
                    toJvm.write(answers.removeFirstOrNull() ?: frame())
                    toJvm.flush()
                }
            }
            quit.countDown()
            runCatching { toJvm.close() }
            exited.complete(0)
        }
            .apply { isDaemon = true }
            .start()
    }

    override fun getOutputStream(): OutputStream = fromJvm

    override fun getInputStream(): InputStream = fromHelper

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exited.get()

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = runCatching {
        exited.get(timeout, unit)
    }
        .isSuccess

    override fun exitValue(): Int =
        if (exited.isDone) exited.get() else throw IllegalThreadStateException("running")

    override fun destroy() {
        destroyForcibly()
    }

    override fun destroyForcibly(): Process {
        destroyed = true
        runCatching { toJvm.close() }
        exited.complete(1)
        return this
    }

    override fun isAlive(): Boolean = !exited.isDone

    override fun pid(): Long = 0

    override fun onExit(): CompletableFuture<Process> = exited.thenApply { this }
}

private fun assertContainsSequence(actual: List<String>, expected: List<String>) {
    val window = actual.windowed(size = expected.size).firstOrNull { it == expected }
    assertTrue(window != null, "Expected argv to contain $expected, got $actual")
}
