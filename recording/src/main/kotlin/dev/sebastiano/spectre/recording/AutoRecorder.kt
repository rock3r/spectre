package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.awt.Rectangle
import java.lang.ProcessHandle
import java.nio.file.Path

/**
 * High-level recorder that picks between window-targeted SCK capture and region-based ffmpeg
 * capture per call, so callers don't have to know which backend is appropriate.
 *
 * Routing logic (in order):
 * 1. `window == null` → ffmpeg region capture. Caller doesn't have a window in mind, or is
 *    capturing an embedded `ComposePanel` where there's no top-level window to target.
 * 2. Non-macOS host → ffmpeg. SCK is macOS-only.
 * 3. macOS host with a window → SCK. If SCK fails because the helper isn't bundled (e.g. a jar
 *    built on Linux running on macOS), falls back to ffmpeg with a stderr warning so the
 *    degradation is visible. **Only** [HelperNotBundledException] triggers fallback — operational
 *    SCK failures (Screen Recording permission denied, target window not found, helper crashed
 *    during init) propagate as `IllegalStateException` so the caller sees the real error rather
 *    than getting a silently-different recording.
 *
 * The router always needs both a window AND a region: the region is used as the fallback when SCK
 * isn't applicable. If you don't have a region (e.g. no clear bounds for a missing window),
 * instantiate [ScreenCaptureKitRecorder] or [FfmpegRecorder] directly.
 */
class AutoRecorder
internal constructor(
    private val sckRecorder: WindowRecorder,
    private val ffmpegRecorder: Recorder,
    private val isMacOs: () -> Boolean,
) {

    constructor(
        sckRecorder: WindowRecorder = ScreenCaptureKitRecorder(),
        ffmpegRecorder: Recorder = FfmpegRecorder(),
    ) : this(sckRecorder, ffmpegRecorder, ::defaultIsMacOs)

    fun start(
        window: TitledWindow?,
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
        windowOwnerPid: Long = ProcessHandle.current().pid(),
    ): RecordingHandle {
        if (window == null || !isMacOs()) {
            return ffmpegRecorder.start(region, output, options)
        }
        return try {
            sckRecorder.start(
                window = window,
                windowOwnerPid = windowOwnerPid,
                output = output,
                options = options,
            )
        } catch (e: HelperNotBundledException) {
            // The helper isn't in the jar. This is the cross-platform-jar case (built on
            // Linux, run on macOS) — degrade to region capture rather than throwing, but
            // surface a stderr warning so the silent downgrade is at least visible.
            System.err.println(
                "AutoRecorder: SCK helper not bundled (${e.message}); falling back to ffmpeg region capture."
            )
            ffmpegRecorder.start(region, output, options)
        }
    }

    private companion object {
        @JvmStatic
        fun defaultIsMacOs(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("mac")
    }
}
