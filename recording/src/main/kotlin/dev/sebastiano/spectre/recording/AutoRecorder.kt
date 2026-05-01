package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.awt.Rectangle
import java.lang.ProcessHandle
import java.nio.file.Path

/**
 * High-level recorder that picks between window-targeted capture and region-based ffmpeg capture
 * per call, so callers don't have to know which backend is appropriate.
 *
 * Routing logic (in order):
 * 1. `window == null` → ffmpeg region capture. Caller doesn't have a window in mind, or is
 *    capturing an embedded `ComposePanel` where there's no top-level window to target.
 * 2. macOS host with a window → SCK ([WindowRecorder]). If SCK fails because the helper isn't
 *    bundled (e.g. a jar built on Linux running on macOS), falls back to ffmpeg region capture with
 *    a stderr warning so the degradation is visible. **Only** [HelperNotBundledException] triggers
 *    fallback — operational SCK failures (Screen Recording permission denied, target window not
 *    found, helper crashed during init) propagate as `IllegalStateException` so the caller sees the
 *    real error rather than getting a silently-different recording.
 * 3. Non-macOS host with a window whose [TitledWindow.title] is non-blank, AND a non-null
 *    [windowsWindowRecorder] — uses [FfmpegWindowRecorder] (gdigrab `title=` capture). This is the
 *    Windows window-targeted path: window movement is followed automatically and occlusion doesn't
 *    matter. Jewel-in-IDE tool windows have no top-level title so they fall through to the region
 *    path (see step 4).
 * 4. Non-macOS host without an applicable [windowsWindowRecorder] OR with a blank/null title →
 *    ffmpeg region capture. The region path is the documented fallback when the target window title
 *    is missing, ambiguous, or points at a tool window with no top-level title. The underlying
 *    [FfmpegRecorder]'s [FfmpegBackend.detect] picks the platform device: gdigrab on Windows,
 *    x11grab on Linux. Wayland-without-XWayland sessions surface as a clear ffmpeg-side "cannot
 *    open display" at spawn time — Wayland-native capture is a separate, future backend.
 *
 * The router always needs both a window AND a region: the region is used as the fallback when the
 * window-targeted path isn't applicable. If you don't have a region (e.g. no clear bounds for a
 * missing window), instantiate [ScreenCaptureKitRecorder], [FfmpegWindowRecorder], or
 * [FfmpegRecorder] directly.
 */
class AutoRecorder
internal constructor(
    private val sckRecorder: WindowRecorder,
    private val ffmpegRecorder: Recorder,
    private val windowsWindowRecorder: WindowRecorder?,
    private val isMacOs: () -> Boolean,
    private val isWindows: () -> Boolean,
) {

    constructor(
        sckRecorder: WindowRecorder = ScreenCaptureKitRecorder(),
        ffmpegRecorder: Recorder = FfmpegRecorder(),
        windowsWindowRecorder: WindowRecorder? = defaultWindowsWindowRecorder(),
    ) : this(
        sckRecorder,
        ffmpegRecorder,
        windowsWindowRecorder,
        ::defaultIsMacOs,
        ::defaultIsWindows,
    )

    fun start(
        window: TitledWindow?,
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
        windowOwnerPid: Long = ProcessHandle.current().pid(),
    ): RecordingHandle {
        if (window == null) {
            return ffmpegRecorder.start(region, output, options)
        }
        if (isMacOs()) {
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
                    "AutoRecorder: SCK helper not bundled (${e.message}); falling back to " +
                        "ffmpeg region capture."
                )
                ffmpegRecorder.start(region, output, options)
            }
        }
        // Non-mac path. Try title-based window capture if we have a Windows window recorder
        // wired up AND a non-blank title to feed it; otherwise fall through to region.
        val titledRecorder = windowsWindowRecorder
        val title = window.title
        if (titledRecorder != null && isWindows() && !title.isNullOrBlank()) {
            return titledRecorder.start(
                window = window,
                windowOwnerPid = windowOwnerPid,
                output = output,
                options = options,
            )
        }
        return ffmpegRecorder.start(region, output, options)
    }

    private companion object {
        @JvmStatic
        fun defaultIsMacOs(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("mac")

        @JvmStatic
        fun defaultIsWindows(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("windows")

        /**
         * Constructs a [FfmpegWindowRecorder] only when the host is Windows (the only OS where
         * gdigrab is available). On other hosts the public [AutoRecorder] constructor still works —
         * the windowsWindowRecorder slot stays null and the router falls through to ffmpeg region
         * capture.
         *
         * Failures resolving the ffmpeg path or constructing the recorder are deliberately
         * swallowed: callers on a Windows host without ffmpeg on `PATH` should still be able to
         * instantiate [AutoRecorder] (e.g. for region capture against an alternate backend, or just
         * to read the API surface in a unit test). The recorder construction would throw with a
         * clear message at first `start()` call instead.
         */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun defaultWindowsWindowRecorder(): WindowRecorder? {
            if (!defaultIsWindows()) return null
            return try {
                FfmpegWindowRecorder()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
