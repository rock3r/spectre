package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Rectangle
import java.nio.file.Path

/** Records a requested Wayland window, with its screen bounds at recording start. */
public interface WaylandWindowSourceRecorder {
    public fun start(
        window: TitledWindow,
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle
}

/**
 * Retained for API compatibility. The ScreenCast portal cannot attest that its selected window
 * belongs to the requested process or that it is the requested window.
 */
public class WaylandPortalWindowRecorder : WaylandWindowSourceRecorder {
    /** Fails before prompting, restoring a grant, or creating an output file. */
    override fun start(
        window: TitledWindow,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        error(
            "The Wayland portal cannot verify the selected window's identity. Use " +
                "AutoRecorder.startWindow with an in-process AWT window on GNOME/Mutter, " +
                "or request desktop pixels explicitly with AutoRecorder.startRegion."
        )
}
