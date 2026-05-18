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
                HelperArguments(
                        mode = "screenshot",
                        pid = windowOwnerPid,
                        titleContains = discriminator.value,
                        output = output,
                        fps = SCREENSHOT_FRAME_RATE,
                        captureCursor = false,
                        discoveryTimeoutMs = SCREENSHOT_DISCOVERY_TIMEOUT_MS,
                    )
                    .toArgv(helperPath)
            process = processFactory.start(argv)
            val exit = process.waitFor()
            check(exit == 0) { messageForHelperExit(exit, output, argv) }
            return ImageIO.read(output.toFile())
                ?: error("spectre-screencapture did not produce a readable PNG at $output")
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            throw IllegalStateException("spectre-screencapture screenshot failed", e)
        } finally {
            try {
                val wasInterrupted = Thread.interrupted()
                try {
                    discriminator.restore()
                } finally {
                    if (wasInterrupted) Thread.currentThread().interrupt()
                }
            } finally {
                Files.deleteIfExists(output)
            }
        }
    }
}

private const val SCREENSHOT_DISCOVERY_TIMEOUT_MS: Int = 2000
private const val SCREENSHOT_FRAME_RATE: Int = 30
private const val EXIT_ARGUMENTS_REJECTED: Int = 2
private const val EXIT_WINDOW_NOT_FOUND: Int = 3
private const val EXIT_SCREEN_RECORDING_DENIED: Int = 4
private const val EXIT_CAPTURE_FAILED: Int = 5

private fun messageForHelperExit(
    exit: Int,
    output: java.nio.file.Path,
    argv: List<String>,
): String =
    when (exit) {
        EXIT_ARGUMENTS_REJECTED ->
            "spectre-screencapture rejected its screenshot arguments (exit 2). Argv: $argv"
        EXIT_WINDOW_NOT_FOUND ->
            "spectre-screencapture could not find the screenshot target window within the " +
                "discovery timeout (exit 3). Argv: $argv"
        EXIT_SCREEN_RECORDING_DENIED ->
            "spectre-screencapture was denied Screen Recording permission for screenshot capture " +
                "(exit 4). Grant the JVM Screen Recording permission and restart it. Argv: $argv"
        EXIT_CAPTURE_FAILED ->
            "spectre-screencapture's screenshot pipeline failed (exit 5) — output at $output is " +
                "in an undefined state. Argv: $argv"
        else ->
            "spectre-screencapture exited with code $exit during screenshot capture. Argv: $argv"
    }
