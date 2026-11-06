package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.WindowScreenshotter
import dev.sebastiano.spectre.recording.screencapturekit.FramedWindow
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.image.BufferedImage
import java.io.IOException

/**
 * Windows still screenshot backend backed by the bundled .NET native helper.
 *
 * The helper runs in `--mode stills` and keeps its capture session open between stills of the same
 * window, so repeated stills (visual-idle sampling) do not pay helper startup each time (#612). See
 * [WindowsStillSessions].
 */
public class WindowsWindowScreenshotter
internal constructor(
    private val helperExtractor: WindowsGraphicsCaptureHelperBinaryExtractor,
    private val processFactory: ProcessFactory,
    private val stillSessions: WindowsStillSessions = sharedStillSessions,
) : WindowScreenshotter {

    public constructor() :
        this(DefaultWindowsGraphicsCaptureHelperBinaryExtractor.instance, SystemProcessFactory)

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        val framed = window as? FramedWindow
        val title = if (framed == null) window.title else null
        require(framed != null || !title.isNullOrBlank()) {
            "WindowsWindowScreenshotter requires a non-blank window title; got \"$title\"."
        }
        val helperPath = helperExtractor.extract()
        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Stills,
                    source = WindowsGraphicsCaptureSource.Window,
                    title = title,
                    frame = framed?.deviceFrame,
                    ownerPid = windowOwnerPid,
                    output = null,
                    fps = 30,
                    captureCursor = false,
                )
                .toArgv(helperPath)
        return try {
            stillSessions.capture(argv, processFactory::start)
        } catch (e: IOException) {
            throw IllegalStateException(
                "spectre-window-capture failed to start. Native Windows window capture " +
                    "requires .NET 8 Desktop Runtime, Windows App Runtime 1.8, and Windows " +
                    "Graphics Capture support.",
                e,
            )
        }
    }

    internal interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    private object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            startPumpedHelperProcess(argv) {
                redirectOutput(ProcessBuilder.Redirect.PIPE)
                redirectInput(ProcessBuilder.Redirect.PIPE)
            }
    }

    private companion object {
        // One pool per JVM, so every screenshotter instance reuses the same open sessions.
        val sharedStillSessions: WindowsStillSessions by lazy { WindowsStillSessions() }
    }
}
