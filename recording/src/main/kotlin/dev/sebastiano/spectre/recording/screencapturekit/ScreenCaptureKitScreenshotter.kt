package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.HelperStillSessions
import dev.sebastiano.spectre.recording.StillHelper
import dev.sebastiano.spectre.recording.WindowScreenshotter
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * macOS still screenshot backend backed by the bundled ScreenCaptureKit helper.
 *
 * A window that the helper can find again by the same selector, an untitled window by its frame or
 * a window by its unique title, is captured from a `--mode stills` session that stays open between
 * stills (#605). Each new ScreenCaptureKit capture costs about a second in steady state, which made
 * visual-idle samples with more than one window run out of their budget. See [HelperStillSessions].
 *
 * A window whose title needs a [TitleDiscriminator] gets a new suffix for every still, so it keeps
 * the one-shot `--mode screenshot` helper.
 */
public class ScreenCaptureKitScreenshotter
internal constructor(
    private val helperExtractor: HelperBinaryExtractor,
    private val processFactory: ScreenCaptureKitRecorder.ProcessFactory,
    private val requireScreenCaptureAccess: () -> Unit = {
        MacOsScreenCaptureAccess.requireGranted(helperExtractor)
    },
    private val stillSessions: HelperStillSessions = sharedStillSessions,
) : WindowScreenshotter {

    public constructor() :
        this(HelperBinaryExtractor(), ScreenCaptureKitRecorder.SystemProcessFactory)

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        val framed = window as? FramedWindow
        if (framed != null) {
            return captureFromSession(windowOwnerPid, titleContains = null, frame = framed)
        }
        if ((window as? TitleDiscriminationAware)?.requiresTitleDiscriminator == false) {
            val title = checkNotNull(window.title)
            return captureFromSession(windowOwnerPid, titleContains = title, frame = null)
        }
        return captureOneShot(window, windowOwnerPid)
    }

    private fun captureFromSession(
        windowOwnerPid: Long,
        titleContains: String?,
        frame: FramedWindow?,
    ): BufferedImage {
        val argv =
            HelperArguments(
                    mode = HelperArguments.STILLS_MODE,
                    source = HelperSource.Window,
                    pid = windowOwnerPid,
                    titleContains = titleContains,
                    frame = frame?.logicalFrame,
                    output = null,
                    fps = SCREENSHOT_FRAME_RATE,
                    captureCursor = false,
                    discoveryTimeoutMs = SCREENSHOT_DISCOVERY_TIMEOUT_MS,
                )
                .toArgv(helperExtractor.extract())
        return try {
            stillSessions.capture(argv) { command ->
                // Fail fast before SCStream so agents never hang on an implicit TCC prompt
                // (#187). An open session already passed this check.
                requireScreenCaptureAccess()
                processFactory.start(command)
            }
        } catch (e: IOException) {
            throw IllegalStateException("spectre-screencapture failed to start", e)
        }
    }

    private fun captureOneShot(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        // Fail fast before SCStream so agents never hang on an implicit TCC prompt (#187).
        requireScreenCaptureAccess()
        val helperPath = helperExtractor.extract()
        val output = Files.createTempFile("spectre-sck-window-screenshot-", ".png")
        val framed = window as? FramedWindow
        val discriminator =
            if ((window as? TitleDiscriminationAware)?.requiresTitleDiscriminator == false) {
                null
            } else {
                TitleDiscriminator(window)
            }
        var process: Process? = null
        discriminator?.apply()
        try {
            val argv =
                HelperArguments(
                        mode = "screenshot",
                        source = HelperSource.Window,
                        pid = windowOwnerPid,
                        titleContains =
                            if (framed != null) null
                            else discriminator?.value ?: checkNotNull(window.title),
                        frame = framed?.logicalFrame,
                        output = output,
                        fps = SCREENSHOT_FRAME_RATE,
                        captureCursor = false,
                        discoveryTimeoutMs = SCREENSHOT_DISCOVERY_TIMEOUT_MS,
                    )
                    .toArgv(helperPath)
            process = processFactory.start(argv)
            if (!process.waitFor(SCREENSHOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                process.waitFor(FORCE_KILL_WAIT_SECONDS, TimeUnit.SECONDS)
                error(
                    "spectre-screencapture screenshot timed out after ${SCREENSHOT_TIMEOUT_SECONDS}s"
                )
            }
            val exit = process.exitValue()
            check(exit == 0) { messageForHelperExit(exit, argv, output) }
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
                    discriminator?.restore()
                } finally {
                    if (wasInterrupted) Thread.currentThread().interrupt()
                }
            } finally {
                Files.deleteIfExists(output)
            }
        }
    }

    private companion object {
        // One pool per JVM, so every screenshotter instance reuses the same open sessions.
        val sharedStillSessions: HelperStillSessions by lazy {
            HelperStillSessions(ScreenCaptureKitStillHelper)
        }
    }
}

/** The macOS `spectre-screencapture` helper in `--mode stills`. */
internal object ScreenCaptureKitStillHelper : StillHelper {
    override val name: String = "spectre-screencapture"

    // Stopping a ScreenCaptureKit stream takes most of a second; do not make the caller wait.
    override val closeWaitMillis: Long = 0

    override fun failureMessage(
        process: Process,
        argv: List<String>,
        exit: Int?,
        message: String,
    ): String = if (exit == null) "$message Argv: $argv" else messageForHelperExit(exit, argv)

    override fun timeoutMessage(process: Process, message: String): String = message
}

private const val SCREENSHOT_DISCOVERY_TIMEOUT_MS: Int = 2000
private const val SCREENSHOT_FRAME_RATE: Int = 30
// Allow the helper's 2s window discovery and 10s first-frame budgets, plus startup slack.
private const val SCREENSHOT_TIMEOUT_SECONDS: Long = 15
private const val FORCE_KILL_WAIT_SECONDS: Long = 1
private const val EXIT_ARGUMENTS_REJECTED: Int = 2
private const val EXIT_WINDOW_NOT_FOUND: Int = 3
private const val EXIT_SCREEN_RECORDING_DENIED: Int = 4
private const val EXIT_CAPTURE_FAILED: Int = 5
private const val EXIT_WINDOW_AMBIGUOUS: Int = 7

private fun messageForHelperExit(
    exit: Int,
    argv: List<String>,
    output: java.nio.file.Path? = null,
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
            "spectre-screencapture's screenshot pipeline failed (exit 5)" +
                (if (output != null) " — output at $output is in an undefined state" else "") +
                ". Argv: $argv"
        EXIT_WINDOW_AMBIGUOUS ->
            "spectre-screencapture found more than one window with the target frame (exit 7), " +
                "so it cannot tell which one to capture. Move or resize one of the windows. " +
                "Argv: $argv"
        else ->
            "spectre-screencapture exited with code $exit during screenshot capture. Argv: $argv"
    }
