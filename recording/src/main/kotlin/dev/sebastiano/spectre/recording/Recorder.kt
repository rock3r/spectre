package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Captures a region of the screen to a video file.
 *
 * The v1 default is [FfmpegRecorder] which shells out to a system `ffmpeg` binary. Tests and
 * advanced consumers can swap in their own [Recorder] implementation (e.g. an in-memory frame
 * accumulator, or the future ScreenCaptureKit-backed window-targeted recorder tracked in v2).
 */
interface Recorder {

    /**
     * Begin recording [region] to [output]. Returns a [RecordingHandle] that the caller stops to
     * finalise the file. Implementations must spawn whatever they need eagerly so that callers can
     * drive the UI immediately afterwards — by the time `start` returns, frames should be landing
     * in the output file.
     */
    fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle
}
