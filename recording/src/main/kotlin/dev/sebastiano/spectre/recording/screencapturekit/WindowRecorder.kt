package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Rectangle
import java.lang.ProcessHandle
import java.nio.file.Path

/**
 * Window-targeted recorder surface — captures the pixels of a single window identified by
 * [TitledWindow] regardless of where on screen it sits or what's overlapping it. Implemented by
 * [ScreenCaptureKitRecorder] on macOS and [WindowsGraphicsCaptureRecorder] on Windows.
 *
 * Distinct from `Recorder` (which is region-based) because window-targeted capture has
 * fundamentally different ergonomics — coordinates aren't necessary, occlusion isn't a concern,
 * window movement is automatically followed.
 *
 * For handle-less embedded surfaces (ComposePanel inside a host top-level window), use
 * [startCropped] so the host window is captured and cropped to the surface bounds (#186).
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

    /**
     * Capture [window] and crop output to [cropInWindow].
     *
     * [cropInWindow] is relative to the window's top-left in **AWT user space** (same units as
     * `Component.bounds` / window-identity `surfaceBoundsInWindow`). Backends convert to the units
     * their native helpers expect (SCK points on macOS; device pixels on Windows when
     * [scaleX]/[scaleY] are supplied).
     *
     * **v1 semantics:** the crop rectangle is fixed at [startCropped] and is **not** updated if the
     * surface is moved or resized mid-recording. Callers that can detect a bounds change should
     * stop and restart, or accept misaligned frames.
     *
     * Default implementation throws; platforms that support window+crop override it.
     */
    public fun startCropped(
        window: TitledWindow,
        cropInWindow: Rectangle,
        windowOwnerPid: Long = ProcessHandle.current().pid(),
        output: Path,
        options: RecordingOptions = RecordingOptions(),
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
    ): RecordingHandle {
        throw UnsupportedOperationException(
            "${this::class.simpleName} does not support window capture + crop. " +
                "Use startRegion(...) only as an explicit last resort, or a platform backend " +
                "that implements startCropped (macOS ScreenCaptureKit / Windows Graphics Capture)."
        )
    }
}
