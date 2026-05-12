package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.lang.ProcessHandle
import java.nio.file.Path

/**
 * Window-targeted recorder surface — captures the pixels of a single window identified by
 * [TitledWindow] regardless of where on screen it sits or what's overlapping it. Implemented by
 * [ScreenCaptureKitRecorder] on macOS; future Windows / Linux backends would implement the same
 * interface.
 *
 * Distinct from `Recorder` (which is region-based) because window-targeted capture has
 * fundamentally different ergonomics — coordinates aren't necessary, occlusion isn't a concern,
 * window movement is automatically followed.
 *
 * The high-level [dev.sebastiano.spectre.recording.AutoRecorder] router takes both a
 * [WindowRecorder] and a [dev.sebastiano.spectre.recording.Recorder] and picks the right one per
 * call based on whether a window was supplied + the current host OS.
 */
public interface WindowRecorder {
    public fun start(
        window: TitledWindow,
        windowOwnerPid: Long = ProcessHandle.current().pid(),
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle
}
