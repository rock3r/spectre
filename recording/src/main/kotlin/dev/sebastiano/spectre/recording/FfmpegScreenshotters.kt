package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/** Windows still screenshot backend using ffmpeg's `gdigrab title=` window source. */
public class FfmpegWindowScreenshotter
internal constructor(
    private val ffmpegPath: Path,
    private val processFactory: FfmpegRecorder.ProcessFactory,
) : WindowScreenshotter {

    public constructor(
        ffmpegPath: Path = FfmpegRecorder.resolveFfmpegPath()
    ) : this(ffmpegPath, FfmpegRecorder.SystemProcessFactory)

    init {
        require(Files.isExecutable(ffmpegPath) || ffmpegPath == FfmpegRecorder.PROBE_PATH) {
            "ffmpeg binary not executable at $ffmpegPath"
        }
    }

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        val title = window.title
        require(!title.isNullOrBlank()) {
            "FfmpegWindowScreenshotter requires a non-blank window title; got \"$title\"."
        }
        return captureFfmpegPng(processFactory) { output ->
            FfmpegCli.gdigrabWindowStillCapture(ffmpegPath, title, output)
        }
    }
}

/** Linux X11 still screenshot backend using ffmpeg's `x11grab` region source. */
public class FfmpegRegionScreenshotter
internal constructor(
    private val ffmpegPath: Path,
    private val processFactory: FfmpegRecorder.ProcessFactory,
    private val displayNameProvider: () -> String?,
    private val getenv: (String) -> String?,
) : RegionScreenshotter {

    public constructor(
        ffmpegPath: Path = FfmpegRecorder.resolveFfmpegPath()
    ) : this(
        ffmpegPath,
        FfmpegRecorder.SystemProcessFactory,
        { System.getenv("DISPLAY") },
        System::getenv,
    )

    init {
        require(Files.isExecutable(ffmpegPath) || ffmpegPath == FfmpegRecorder.PROBE_PATH) {
            "ffmpeg binary not executable at $ffmpegPath"
        }
    }

    override fun captureRegion(region: Rectangle): BufferedImage =
        captureFfmpegPng(processFactory) { output ->
            FfmpegBackend.checkNotWayland(getenv)
            FfmpegCli.x11grabRegionStillCapture(
                ffmpegPath = ffmpegPath,
                region = region,
                output = output,
                displayName = displayNameProvider()?.takeIf { it.isNotBlank() } ?: ":0.0",
            )
        }
}

private fun captureFfmpegPng(
    processFactory: FfmpegRecorder.ProcessFactory,
    argvBuilder: (Path) -> List<String>,
): BufferedImage {
    val output = Files.createTempFile("spectre-window-screenshot-", ".png")
    var process: Process? = null
    try {
        val argv = argvBuilder(output)
        process = processFactory.start(argv)
        val exit = process.waitFor()
        check(exit == 0) { "ffmpeg screenshot capture exited with code $exit. Argv: $argv" }
        return ImageIO.read(output.toFile())
            ?: error("ffmpeg screenshot capture did not produce a readable PNG at $output")
    } catch (e: InterruptedException) {
        process?.destroyForcibly()
        Thread.currentThread().interrupt()
        throw e
    } catch (e: IOException) {
        throw IllegalStateException("ffmpeg screenshot capture failed", e)
    } finally {
        Files.deleteIfExists(output)
    }
}
