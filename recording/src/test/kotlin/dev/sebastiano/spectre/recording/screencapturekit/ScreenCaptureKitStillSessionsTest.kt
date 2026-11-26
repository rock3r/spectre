package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.HelperStillSessions
import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Rectangle
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

/**
 * #605: every macOS window still used to start a new helper and a new ScreenCaptureKit capture,
 * which costs about a second in steady state. Visual idle with an owner window and a JWindow popup
 * took two such stills per sample and ran out of its sample budget. The screenshotter now keeps one
 * `--mode stills` helper per window and asks it for each still.
 */
class ScreenCaptureKitStillSessionsTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `direct AWT stills reuse the session for each shared-title frame`() {
        withSharedSckFrames { left, right ->
            val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame(), frame())) }
            val stills = screenshotter(factory)
            for (window in listOf(left, right, left)) {
                stills.captureWindow(window.asTitledWindow(), ProcessHandle.current().pid())
            }
            assertEquals(2, factory.started.size)
            assertContainsSequence(factory.started[0], listOf("--frame", "40,80,300,200"))
            assertContainsSequence(factory.started[1], listOf("--frame", "410,80,300,200"))
        }
    }

    @Test
    fun `repeated stills of an untitled window reuse one helper and one permission check`() {
        val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame(), frame())) }
        val accessChecks = AtomicInteger()
        val screenshotter = screenshotter(factory, onAccessCheck = accessChecks::incrementAndGet)
        val popup =
            FramedWindow(
                logicalFrame = Rectangle(-10, 20, 240, 160),
                deviceFrame = Rectangle(-20, 40, 480, 320),
            )

        val first = screenshotter.captureWindow(popup, 4242L)
        val second = screenshotter.captureWindow(popup, 4242L)

        assertEquals(1, factory.started.size)
        assertEquals(1, accessChecks.get())
        val argv = factory.started.single()
        assertContainsSequence(argv, listOf("--mode", "stills", "--source", "window"))
        assertContainsSequence(argv, listOf("--pid", "4242"))
        assertContainsSequence(argv, listOf("--frame", "-10,20,240,160"))
        assertTrue("--output" !in argv, "argv: $argv")
        assertEquals(2, first.width)
        assertEquals(1, first.height)
        assertEquals(0xFF030201.toInt(), first.getRGB(0, 0))
        assertEquals(first.getRGB(1, 0), second.getRGB(1, 0))
    }

    @Test
    fun `a uniquely titled window is selected by its title in a session`() {
        val factory = StillsProcessFactory { StillsHelper(frames = listOf(frame())) }
        val window = UniqueTitledWindow("Spectre Sample")

        screenshotter(factory).captureWindow(window, 4242L)

        assertContainsSequence(
            factory.started.single(),
            listOf("--title-contains", "Spectre Sample"),
        )
        assertEquals("Spectre Sample", window.title)
    }

    @Test
    fun `shared-title windows with distinct frames keep and reuse separate sessions`() {
        val helpers =
            ArrayDeque(
                listOf(
                    StillsHelper(frames = listOf(solidFrame(0x11), solidFrame(0x12))),
                    StillsHelper(frames = listOf(solidFrame(0x21))),
                )
            )
        val factory = StillsProcessFactory { helpers.removeFirst() }
        val screenshotter = screenshotter(factory)
        val left = framedWindow(Rectangle(10, 20, 300, 200))
        val right = framedWindow(Rectangle(410, 20, 300, 200))

        val leftFirst = screenshotter.captureWindow(left, 4242L)
        val rightFirst = screenshotter.captureWindow(right, 4242L)
        val leftSecond = screenshotter.captureWindow(left, 4242L)

        assertEquals(2, factory.started.size)
        assertContainsSequence(factory.started[0], listOf("--frame", "10,20,300,200"))
        assertContainsSequence(factory.started[1], listOf("--frame", "410,20,300,200"))
        assertEquals(0xFF000011.toInt(), leftFirst.getRGB(0, 0))
        assertEquals(0xFF000021.toInt(), rightFirst.getRGB(0, 0))
        assertEquals(0xFF000012.toInt(), leftSecond.getRGB(0, 0))
    }

    @Test
    fun `a frame timeout kills the normal stills-session helper`() {
        val helper = StillsHelper(respondToCaptures = false)
        val factory = StillsProcessFactory { helper }

        val error =
            assertFailsWith<IllegalStateException> {
                screenshotter(factory, frameTimeoutMillis = 20)
                    .captureWindow(framedWindow(Rectangle(10, 20, 300, 200)), 4242L)
            }

        assertContains(error.message.orEmpty(), "Timed out after 20ms")
        assertTrue(helper.destroyed, "a timed-out stills helper must be killed")
    }

    @Test
    fun `a stale session is replaced by a new one`() {
        val helpers =
            ArrayDeque(
                listOf(StillsHelper(frames = listOf(STALE)), StillsHelper(frames = listOf(frame())))
            )
        val factory = StillsProcessFactory { helpers.removeFirst() }

        val image = screenshotter(factory).captureWindow(UniqueTitledWindow("Resized"), 4242L)

        assertEquals(2, factory.started.size)
        assertEquals(2, image.width)
    }

    @Test
    fun `closing an idle session does not wait for the helper to stop its stream`() {
        val helper = StillsHelper(frames = listOf(frame()), exitsOnQuit = false)
        val factory = StillsProcessFactory { helper }

        screenshotter(factory, idleCloseMillis = 50)
            .captureWindow(UniqueTitledWindow("Spectre"), 4242L)

        assertTrue(helper.quit.await(5, TimeUnit.SECONDS), "the idle helper was not told to quit")
        assertTrue(!helper.destroyed, "a helper that is stopping its stream must not be killed")
    }

    @Test
    fun `a helper that cannot find the window reports the lookup miss`() {
        val factory = StillsProcessFactory { StillsHelper(exitBeforeReady = 3) }

        val error =
            assertFailsWith<IllegalStateException> {
                screenshotter(factory).captureWindow(UniqueTitledWindow("Missing"), 4242L)
            }

        assertContains(error.message.orEmpty(), "could not find")
    }

    @Test
    fun `a recording stops the open stills sessions before it starts its capture`() {
        // On macOS 27.2 a new ScreenCaptureKit capture gets no frames while a stills session
        // still holds its stream, so a recording right after a visual-idle wait failed with
        // "timed out waiting for first recording frame".
        val stills = StillsHelper(frames = listOf(frame()))
        val recording = StillsHelper()
        var stillsAliveAtRecordingStart: Boolean? = null
        val helpers =
            ArrayDeque<() -> StillsHelper>(
                listOf(
                    { stills },
                    {
                        stillsAliveAtRecordingStart = stills.isAlive
                        recording
                    },
                )
            )
        val factory = StillsProcessFactory { helpers.removeFirst()() }
        val sessions = stillSessions()
        screenshotter(factory, stillSessions = sessions)
            .captureWindow(UniqueTitledWindow("Spectre"), 4242L)
        val output = Files.createTempFile("spectre-sck-after-stills-", ".mp4")
        Files.write(output, byteArrayOf(1))
        try {
            recorder(factory, sessions)
                .start(UniqueTitledWindow("Spectre"), 4242L, output, RecordingOptions())
                .use { Files.write(output, byteArrayOf(1, 2)) }
        } finally {
            output.deleteIfExists()
        }

        assertEquals(false, stillsAliveAtRecordingStart)
    }

    @Test
    fun `recording kills a prior stills session that does not shut down`() {
        val stills = StillsHelper(frames = listOf(frame()), exitsOnQuit = false)
        val recording = StillsHelper()
        val helpers = ArrayDeque(listOf(stills, recording))
        val factory = StillsProcessFactory { helpers.removeFirst() }
        val sessions = stillSessions(stopWaitMillis = 20)
        val screenshotter = ScreenCaptureKitScreenshotter(fakeExtractor(), factory, {}, sessions)
        screenshotter.captureWindow(framedWindow(Rectangle(10, 20, 300, 200)), 4242L)
        val output = Files.createTempFile("spectre-sck-kill-stills-", ".mp4")
        try {
            recorder(factory, sessions)
                .start(UniqueTitledWindow("Spectre"), 4242L, output, RecordingOptions())
                .use { Files.write(output, byteArrayOf(1)) }
        } finally {
            output.deleteIfExists()
        }

        assertTrue(stills.destroyed, "a stuck prior stills session must be killed")
    }

    @Test
    fun `interruption while stopping prior stills kills the helper`() {
        val stills = StillsHelper(frames = listOf(frame()), exitsOnQuit = false)
        val factory = StillsProcessFactory { stills }
        val sessions = stillSessions(stopWaitMillis = 5_000)
        screenshotter(factory, stillSessions = sessions)
            .captureWindow(framedWindow(Rectangle(10, 20, 300, 200)), 4242L)
        try {
            Thread.currentThread().interrupt()
            assertFailsWith<InterruptedException> { sessions.stopAll() }
            assertTrue(stills.destroyed, "an interrupted shutdown must kill the prior helper")
        } finally {
            Thread.interrupted()
        }
    }

    private fun screenshotter(
        factory: StillsProcessFactory,
        idleCloseMillis: Long = 60_000,
        stopWaitMillis: Long = 5_000,
        frameTimeoutMillis: Long = 5_000,
        onAccessCheck: () -> Unit = {},
        stillSessions: HelperStillSessions =
            stillSessions(idleCloseMillis, stopWaitMillis, frameTimeoutMillis),
    ): ScreenCaptureKitScreenshotter =
        ScreenCaptureKitScreenshotter(
            helperExtractor = fakeExtractor(),
            processFactory = factory,
            requireScreenCaptureAccess = { onAccessCheck() },
            stillSessions = stillSessions,
        )

    private fun recorder(
        factory: StillsProcessFactory,
        stillSessions: HelperStillSessions,
    ): ScreenCaptureKitRecorder =
        ScreenCaptureKitRecorder(
            helperExtractor = fakeExtractor(),
            processFactory = factory,
            requireScreenCaptureAccess = {},
            stillSessions = stillSessions,
        )

    private fun stillSessions(
        idleCloseMillis: Long = 60_000,
        stopWaitMillis: Long = 5_000,
        frameTimeoutMillis: Long = 5_000,
    ): HelperStillSessions =
        HelperStillSessions(
            helper = ScreenCaptureKitStillHelper,
            idleCloseMillis = idleCloseMillis,
            readyTimeoutMillis = 5_000,
            frameTimeoutMillis = frameTimeoutMillis,
            stopWaitMillis = stopWaitMillis,
        )

    private fun fakeExtractor(): HelperBinaryExtractor =
        HelperBinaryExtractor(
            envLookup = { null },
            sysPropLookup = { null },
            materialLocator = { helperAppBundleMaterial(byteArrayOf(0x01), byteArrayOf()) },
            targetDirProvider = { tempDir },
        )
}

private val STALE: ByteArray = "STALE the window size changed\n".toByteArray(Charsets.US_ASCII)

/** A 2x1 BGRA frame: an opaque pixel, then a partly transparent premultiplied one. */
private fun frame(): ByteArray =
    "FRAME 2 1\n".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x10, 0x20, 0x30, 0x55)

private fun solidFrame(blue: Int): ByteArray =
    "FRAME 2 1\n".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(blue.toByte(), 0, 0, 0xFF.toByte(), blue.toByte(), 0, 0, 0xFF.toByte())

private fun framedWindow(frame: Rectangle): FramedWindow =
    FramedWindow(logicalFrame = frame, deviceFrame = Rectangle(frame))

private class UniqueTitledWindow(title: String) : TitledWindow {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class StillsProcessFactory(
    private val onOneShot: () -> Unit = {},
    private val next: () -> StillsHelper,
) : ScreenCaptureKitRecorder.ProcessFactory {
    val started: MutableList<List<String>> = mutableListOf()

    override fun start(argv: List<String>): Process {
        started += argv
        if ("screenshot" in argv) {
            onOneShot()
            // The one-shot helper writes a PNG and exits.
            val output = Path.of(argv[argv.indexOf("--output") + 1])
            val image =
                java.awt.image.BufferedImage(4, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB)
            assertTrue(ImageIO.write(image, "png", output.toFile()))
            return ExitedProcess()
        }
        return next().also { it.run() }
    }
}

private class ExitedProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false

    override fun pid(): Long = 0

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

/** Speaks the `--mode stills` protocol: READY, then one queued answer per `c` command. */
private class StillsHelper(
    frames: List<ByteArray> = emptyList(),
    private val exitBeforeReady: Int? = null,
    private val exitsOnQuit: Boolean = true,
    private val respondToCaptures: Boolean = true,
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
            toJvm.write("READY\n".toByteArray(Charsets.US_ASCII))
            toJvm.flush()
            val commands = toHelper.bufferedReader(Charsets.US_ASCII)
            while (true) {
                val line = runCatching { commands.readLine() }.getOrNull()
                if (line == null || line == "q") break
                if (line == "c") {
                    if (respondToCaptures) {
                        toJvm.write(answers.removeFirstOrNull() ?: frame())
                        toJvm.flush()
                    }
                }
            }
            quit.countDown()
            // The real helper stops its stream before it exits, which takes a while.
            if (exitsOnQuit) {
                runCatching { toJvm.close() }
                exited.complete(0)
            }
        }
            .apply { isDaemon = true }
            .start()
    }

    override fun getOutputStream(): OutputStream = fromJvm

    override fun getInputStream(): InputStream = fromHelper

    override fun getErrorStream(): InputStream = InputStream.nullInputStream()

    override fun waitFor(): Int = exited.get()

    // Like Process.waitFor, this throws InterruptedException when the caller is interrupted.
    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean =
        try {
            exited.get(timeout, unit)
            true
        } catch (_: TimeoutException) {
            false
        }

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
