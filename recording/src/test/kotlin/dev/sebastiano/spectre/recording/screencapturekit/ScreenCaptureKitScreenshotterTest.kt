package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ScreenCaptureKitScreenshotterTest {

    @Test
    fun `captureWindow spawns helper in screenshot mode and restores title`() {
        val factory = ScreenshotHelperProcessFactory()
        val extractor =
            HelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(byteArrayOf(0x01)) },
                targetDirProvider = { Path.of("/tmp") },
            )
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor = extractor,
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window = ScreenshotFakeTitledWindow(initialTitle = "MyApp")

        val image = screenshotter.captureWindow(window = window, windowOwnerPid = 4242L)

        assertEquals(4, image.width)
        assertEquals(3, image.height)
        assertEquals("MyApp", window.title)
        val argv = factory.lastArgv
        assertContainsSequence(argv, listOf("--mode", "screenshot"))
        assertContainsSequence(argv, listOf("--pid", "4242"))
        assertContains(argv, "--title-contains")
        assertContainsSequence(argv, listOf("--cursor", "false"))
        assertEquals(
            "png",
            Path.of(argv[argv.indexOf("--output") + 1]).fileName.toString().substringAfterLast('.'),
        )
    }

    @Test
    fun `captureWindow destroys helper when wait is interrupted and restores title`() {
        val process = InterruptingScreenshotHelperProcess()
        val factory = ScreenshotHelperProcessFactory(process)
        val extractor =
            HelperBinaryExtractor(
                resourceLocator = { ByteArrayInputStream(byteArrayOf(0x01)) },
                targetDirProvider = { Path.of("/tmp") },
            )
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor = extractor,
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window = ScreenshotFakeTitledWindow(initialTitle = "MyApp")

        try {
            assertFailsWith<InterruptedException> {
                screenshotter.captureWindow(window = window, windowOwnerPid = 4242L)
            }
            assertTrue(process.destroyedForcibly)
            assertEquals("MyApp", window.title)
        } finally {
            Thread.interrupted()
        }
    }
}

private class ScreenshotFakeTitledWindow(initialTitle: String?) : TitledWindow {
    override var title: String? = initialTitle
    override val bounds: Rectangle = Rectangle(0, 0, 100, 100)
}

private class ScreenshotHelperProcessFactory(
    private val process: Process = SuccessfulScreenshotHelperProcess()
) : ScreenCaptureKitRecorder.ProcessFactory {
    var lastArgv: List<String> = emptyList()
        private set

    override fun start(argv: List<String>): Process {
        lastArgv = argv
        val output = Path.of(argv[argv.indexOf("--output") + 1])
        val image = BufferedImage(4, 3, BufferedImage.TYPE_INT_ARGB)
        assertTrue(ImageIO.write(image, "png", output.toFile()))
        return process
    }
}

private class SuccessfulScreenshotHelperProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int = 0

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = true

    override fun exitValue(): Int = 0

    override fun destroy() = Unit

    override fun isAlive(): Boolean = false

    override fun pid(): Long = 0

    override fun toHandle(): ProcessHandle = ProcessHandle.current()

    override fun onExit(): CompletableFuture<Process> = CompletableFuture.completedFuture(this)
}

private class InterruptingScreenshotHelperProcess : Process() {
    var destroyedForcibly: Boolean = false
        private set

    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getErrorStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun waitFor(): Int {
        throw InterruptedException("interrupted")
    }

    override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = false

    override fun exitValue(): Int = throw IllegalThreadStateException("still running")

    override fun destroy() = Unit

    override fun destroyForcibly(): Process {
        destroyedForcibly = true
        return this
    }

    override fun isAlive(): Boolean = !destroyedForcibly

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
