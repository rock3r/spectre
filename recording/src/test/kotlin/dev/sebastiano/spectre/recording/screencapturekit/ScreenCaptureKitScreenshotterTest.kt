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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.io.TempDir

class ScreenCaptureKitScreenshotterTest {
    @TempDir lateinit var tempDir: Path

    @Test
    fun `captureRegion invokes ScreenCaptureKit region source without cursor`() {
        val factory = ScreenshotHelperProcessFactory()
        var accessChecks = 0
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor = testExtractor(tempDir),
                processFactory = factory,
                requireScreenCaptureAccess = { accessChecks += 1 },
            )

        val image = screenshotter.captureRegion(Rectangle(12, 34, 56, 78), displayIndex = 2)

        assertEquals(4, image.width)
        assertEquals(3, image.height)
        assertEquals(1, accessChecks)
        assertContainsSequence(factory.lastArgv, listOf("--source", "region"))
        assertContainsSequence(factory.lastArgv, listOf("--region", "12,34,56,78"))
        assertContainsSequence(factory.lastArgv, listOf("--display-index", "2"))
        assertContainsSequence(factory.lastArgv, listOf("--cursor", "false"))
    }

    @Test
    fun `captureWindow explains a frame that matches several windows`() {
        val factory =
            ScreenshotHelperProcessFactory(process = SuccessfulScreenshotHelperProcess(exit = 7))
        val screenshotter =
            ScreenCaptureKitScreenshotter(
                helperExtractor =
                    HelperBinaryExtractor(
                        envLookup = { null },
                        sysPropLookup = { null },
                        materialLocator = {
                            helperAppBundleMaterial(byteArrayOf(0x01), byteArrayOf())
                        },
                        targetDirProvider = { tempDir },
                    ),
                processFactory = factory,
                requireScreenCaptureAccess = {},
            )
        val window =
            FramedWindow(
                logicalFrame = Rectangle(0, 0, 300, 200),
                deviceFrame = Rectangle(0, 0, 600, 400),
            )

        val error =
            assertFailsWith<IllegalStateException> { screenshotter.captureWindow(window, 4242L) }

        assertTrue(error.message.orEmpty().contains("more than one window"), error.message)
    }
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

private class SuccessfulScreenshotHelperProcess(private val exit: Int = 0) : Process() {
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
