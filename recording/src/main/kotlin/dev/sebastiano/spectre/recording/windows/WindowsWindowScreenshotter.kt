package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.WindowScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.image.BufferedImage
import java.io.IOException
import java.nio.file.Files
import javax.imageio.ImageIO

/** Windows still screenshot backend backed by the bundled .NET native helper. */
public class WindowsWindowScreenshotter
internal constructor(
    private val helperExtractor: WindowsGraphicsCaptureHelperBinaryExtractor,
    private val processFactory: ProcessFactory,
) : WindowScreenshotter {

    public constructor() :
        this(DefaultWindowsGraphicsCaptureHelperBinaryExtractor.instance, SystemProcessFactory)

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        val title = window.title
        require(!title.isNullOrBlank()) {
            "WindowsWindowScreenshotter requires a non-blank window title; got \"$title\"."
        }
        val helperPath = helperExtractor.extract()
        val output = Files.createTempFile("spectre-windows-window-screenshot-", ".png")
        var process: Process? = null
        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Screenshot,
                    source = WindowsGraphicsCaptureSource.Window,
                    title = title,
                    ownerPid = windowOwnerPid,
                    output = output,
                    fps = 30,
                    captureCursor = true,
                )
                .toArgv(helperPath)
        try {
            process = processFactory.start(argv)
            val exit = process.waitFor()
            check(exit == 0) { messageForWindowsGraphicsCaptureHelperExit(exit, argv) }
            return ImageIO.read(output.toFile())
                ?: error("spectre-window-capture did not produce a readable PNG at $output")
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            throw IllegalStateException(
                "spectre-window-capture failed to start. Native Windows window capture " +
                    "requires .NET 8 Desktop Runtime, Windows App Runtime 1.8, and Windows " +
                    "Graphics Capture support.",
                e,
            )
        } finally {
            Files.deleteIfExists(output)
        }
    }

    internal interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    private object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
    }
}
