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

class ScreenCaptureKitScreenshotterTest {

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
                        targetDirProvider = { Path.of("/tmp") },
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
