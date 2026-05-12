package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Captures a region of the screen to a video file.
 *
 * The default region-capture implementation is [FfmpegRecorder], which shells out to a system
 * `ffmpeg` binary. Window-targeted capture lives behind separate
 * [WindowRecorder][dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder]
 * implementations: `ScreenCaptureKitRecorder` on macOS, `FfmpegWindowRecorder` on Windows, and
 * `WaylandPortalWindowRecorder` on Linux Wayland. Tests and advanced consumers can swap in their
 * own [Recorder] implementation (e.g. an in-memory frame accumulator).
 */
public interface Recorder {

    /**
     * Begin recording [region] to [output]. Returns a [RecordingHandle] that the caller stops to
     * finalise the file. Implementations must spawn whatever they need eagerly so that callers can
     * drive the UI immediately afterwards — by the time `start` returns, frames should be landing
     * in the output file.
     */
    public fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle
}
