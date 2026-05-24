package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.CaptureBackend
import dev.sebastiano.spectre.recording.portal.CaptureTarget
import dev.sebastiano.spectre.recording.portal.Command
import dev.sebastiano.spectre.recording.portal.CursorMode
import dev.sebastiano.spectre.recording.portal.DefaultWaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.portal.Region
import dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.awt.Rectangle
import java.nio.file.Path

/** Linux Xorg/Xvfb recorder backed by Spectre's bundled Linux helper and GStreamer. */
public class LinuxX11Recorder
internal constructor(
    private val helperExtractor: WaylandHelperBinaryExtractor,
    private val displayNameProvider: () -> String?,
) : Recorder, WindowRecorder {

    public constructor() :
        this(
            helperExtractor = DefaultWaylandHelperBinaryExtractor.instance,
            displayNameProvider = { System.getenv("DISPLAY") },
        )

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        delegate(CaptureTarget.REGION, windowTitle = null).start(region, output, options)

    override fun start(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val title = window.title
        require(!title.isNullOrBlank()) {
            "LinuxX11Recorder requires a non-blank window title; got \"$title\"."
        }
        return delegate(CaptureTarget.WINDOW, windowTitle = title)
            .start(window.bounds, output, options)
    }

    private fun delegate(target: CaptureTarget, windowTitle: String?): Recorder =
        WaylandPortalRecorder.createForInternalUse(
            helperExtractor = helperExtractor,
            sourceTypes = emptyList(),
            startCommandFactory = { region, output, options ->
                Command.Start(
                    backend = CaptureBackend.X11,
                    target = target,
                    sourceTypes = emptyList(),
                    displayName = displayNameProvider()?.takeIf { it.isNotBlank() },
                    windowTitle = windowTitle,
                    cursorMode =
                        if (options.captureCursor) CursorMode.EMBEDDED else CursorMode.HIDDEN,
                    frameRate = options.frameRate,
                    region =
                        Region(
                            x = region.x,
                            y = region.y,
                            width = region.width,
                            height = region.height,
                        ),
                    output = output.toAbsolutePath().toString(),
                    codec = options.codec,
                )
            },
        )
}
