package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.WindowScreenshotter
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO

/** macOS still screenshot backend backed by the bundled ScreenCaptureKit helper. */
public class ScreenCaptureKitScreenshotter
internal constructor(
    private val helperExtractor: HelperBinaryExtractor,
    private val processFactory: ScreenCaptureKitRecorder.ProcessFactory,
) : WindowScreenshotter {

    public constructor() :
        this(HelperBinaryExtractor(), ScreenCaptureKitRecorder.SystemProcessFactory)

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        val helperPath = helperExtractor.extract()
        val output = Files.createTempFile("spectre-sck-window-screenshot-", ".png")
        val discriminator = TitleDiscriminator(window)
        var process: Process? = null
        discriminator.apply()
        try {
            val argv =
                listOf(
                    helperPath.toString(),
                    "--mode",
                    "screenshot",
                    "--pid",
                    windowOwnerPid.toString(),
                    "--title-contains",
                    discriminator.value,
                    "--output",
                    output.toString(),
                    "--cursor",
                    "false",
                    "--discovery-timeout-ms",
                    SCREENSHOT_DISCOVERY_TIMEOUT_MS.toString(),
                )
            process = processFactory.start(argv)
            val exit = process.waitFor()
            check(exit == 0) { ScreenCaptureKitRecorder.messageForHelperExit(exit, output, argv) }
            return ImageIO.read(output.toFile())
                ?: error("spectre-screencapture did not produce a readable PNG at $output")
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            throw IllegalStateException("spectre-screencapture screenshot failed", e)
        } finally {
            discriminator.restore()
            Files.deleteIfExists(output)
        }
    }
}

private const val SCREENSHOT_DISCOVERY_TIMEOUT_MS: Int = 2000
