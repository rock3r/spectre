package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.AwtWindowBacked
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.awt.Window
import java.nio.file.Path

/**
 * Linux Wayland window recording through a GNOME Mutter window stream (see [WaylandWindowStreams]).
 * It records the window itself, so windows on top of it do not show, and it opens no dialog. The
 * default window recorder of `AutoRecorder` on Wayland.
 */
internal class MutterWindowRecorder(
    private val streams: () -> WaylandWindowStreams<Window> = {
        DefaultWaylandWindowStreams.instance
    }
) : WaylandWindowSourceRecorder {
    /** [region] is ignored: the stream is bound to the window, not to a screen rectangle. */
    override fun start(
        window: TitledWindow,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle = streams().startRecording(window.requireAwtWindow(), null, output, options)

    /** Records [cropInWindow] (AWT units from the window's top-left) of [window]. */
    fun startCropped(
        window: TitledWindow,
        cropInWindow: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        streams().startRecording(window.requireAwtWindow(), cropInWindow, output, options)

    private fun TitledWindow.requireAwtWindow(): Window =
        (this as? AwtWindowBacked)?.awtWindow
            ?: throw WaylandCaptureUnavailableException(
                "Linux Wayland window recording needs a window of this JVM (an AWT Frame, " +
                    "Dialog, or Window adapted with asTitledWindow()), because Spectre binds a " +
                    "GNOME Mutter window stream to it. Got ${javaClass.name}."
            )
}
