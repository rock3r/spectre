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

/**
 * #605: every macOS window still used to start a new helper and a new ScreenCaptureKit capture,
 * which costs about a second in steady state. Visual idle with an owner window and a JWindow popup
 * took two such stills per sample and ran out of its sample budget. The screenshotter now keeps one
 * `--mode stills` helper per window and asks it for each still.
 */
class ScreenCaptureKitStillSessionsTest {

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
    fun `a window that needs a title discriminator keeps the one-shot helper`() {
        // The discriminator changes the title for every still, so a session could not find the
        // window again by the same selector.
        val factory = StillsProcessFactory { StillsHelper() }
        val window = SharedTitleWindow("Shared")

        val image = screenshotter(factory).captureWindow(window, 4242L)

        assertContainsSequence(factory.started.single(), listOf("--mode", "screenshot"))
        assertEquals(4, image.width)
        assertEquals("Shared", window.title)
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
        try {
            recorder(factory, sessions)
                .start(UniqueTitledWindow("Spectre"), 4242L, output, RecordingOptions())
                .stop()
        } finally {
            output.deleteIfExists()
        }

        assertEquals(false, stillsAliveAtRecordingStart)
    }

    @Test
    fun `a one-shot still stops the open stills sessions before it starts its capture`() {
        val stills = StillsHelper(frames = listOf(frame()))
        var stillsAliveAtOneShotStart: Boolean? = null
        val factory =
            StillsProcessFactory(onOneShot = { stillsAliveAtOneShotStart = stills.isAlive }) {
                stills
            }
        val screenshotter = screenshotter(factory)
        screenshotter.captureWindow(UniqueTitledWindow("Spectre"), 4242L)

        screenshotter.captureWindow(SharedTitleWindow("Shared"), 4242L)

        assertEquals(false, stillsAliveAtOneShotStart)
    }

    @Test
    fun `a stills helper that does not stop in time is killed before a new capture`() {
        val stills = StillsHelper(frames = listOf(frame()), exitsOnQuit = false)
        val factory = StillsProcessFactory { stills }
        val screenshotter = screenshotter(factory, stopWaitMillis = 50)
        screenshotter.captureWindow(UniqueTitledWindow("Spectre"), 4242L)

        screenshotter.captureWindow(SharedTitleWindow("Shared"), 4242L)

        assertTrue(stills.destroyed, "a stills helper still holding its stream must be killed")
    }

    @Test
    fun `an interrupt while stills sessions stop kills them and cancels the new capture`() {
        val stills = StillsHelper(frames = listOf(frame()), exitsOnQuit = false)
        val factory = StillsProcessFactory { stills }
        val screenshotter = screenshotter(factory)
        screenshotter.captureWindow(UniqueTitledWindow("Spectre"), 4242L)

        Thread.currentThread().interrupt()
        var stillInterrupted = false
        try {
            assertFailsWith<InterruptedException> {
                screenshotter.captureWindow(SharedTitleWindow("Shared"), 4242L)
            }
            stillInterrupted = Thread.currentThread().isInterrupted
        } finally {
            // Clear the flag so it does not leak into other tests on this thread.
            Thread.interrupted()
        }

        assertTrue(stillInterrupted, "the caller must still see the interrupt")
        assertTrue(stills.destroyed, "an interrupted stop must not leave the stills helper running")
        assertEquals(1, factory.started.size, "a cancelled capture must not start its helper")
    }

    private fun screenshotter(
        factory: StillsProcessFactory,
        idleCloseMillis: Long = 60_000,
        stopWaitMillis: Long = 5_000,
        onAccessCheck: () -> Unit = {},
        stillSessions: HelperStillSessions = stillSessions(idleCloseMillis, stopWaitMillis),
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
    ): HelperStillSessions =
        HelperStillSessions(
            helper = ScreenCaptureKitStillHelper,
            idleCloseMillis = idleCloseMillis,
            readyTimeoutMillis = 5_000,
            frameTimeoutMillis = 5_000,
            stopWaitMillis = stopWaitMillis,
        )

    private fun fakeExtractor(): HelperBinaryExtractor =
        HelperBinaryExtractor(
            envLookup = { null },
            sysPropLookup = { null },
            materialLocator = { helperAppBundleMaterial(byteArrayOf(0x01), byteArrayOf()) },
            targetDirProvider = { Path.of("/tmp") },
        )
}

private val STALE: ByteArray = "STALE the window size changed\n".toByteArray(Charsets.US_ASCII)

/** A 2x1 BGRA frame: an opaque pixel, then a partly transparent premultiplied one. */
private fun frame(): ByteArray =
    "FRAME 2 1\n".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(0x01, 0x02, 0x03, 0xFF.toByte(), 0x10, 0x20, 0x30, 0x55)

private class UniqueTitledWindow(title: String) : TitledWindow, TitleDiscriminationAware {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
    override val requiresTitleDiscriminator: Boolean = false
}

private class SharedTitleWindow(title: String) : TitledWindow, TitleDiscriminationAware {
    override var title: String? = title
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
    override val requiresTitleDiscriminator: Boolean = true
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
                    toJvm.write(answers.removeFirstOrNull() ?: frame())
                    toJvm.flush()
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
