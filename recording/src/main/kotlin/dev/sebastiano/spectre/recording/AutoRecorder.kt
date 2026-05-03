package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.FfmpegBackend.Companion.detectWaylandSession
import dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder
import dev.sebastiano.spectre.recording.portal.WaylandPortalWindowRecorder
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
 * 1. Linux Wayland session (detected via env / runtime-dir signals; see
 *    [FfmpegBackend.detectWaylandSession]) — handled before the `window == null` check below
 *    because `LinuxX11Grab` throws on Wayland and can't be the fallback. Sub-routing:
 *     - `window != null` AND [waylandPortalWindowRecorder] wired → window-targeted portal recorder
 *       (uses `SourceType.WINDOW`; the compositor follows the window across the screen, occlusion
 *       doesn't matter, no region cropping).
 *     - Otherwise (`window == null` OR no window-targeted recorder) AND [waylandPortalRecorder]
 *       wired → region-targeted portal recorder (uses `SourceType.MONITOR`; user picks a monitor at
 *       the dialog, then the compositor crops to [region]). First call pops a permission dialog;
 *       subsequent calls within the same JVM run reuse the grant. See [WaylandPortalRecorder].
 *     - Neither portal recorder wired → falls through to ffmpeg, which fails fast on Wayland.
 * 2. `window == null` → ffmpeg region capture. Caller doesn't have a window in mind, or is
 *    capturing an embedded `ComposePanel` where there's no top-level window to target.
 * 3. macOS host with a window → SCK ([WindowRecorder]). If SCK fails because the helper isn't
 *    bundled (e.g. a jar built on Linux running on macOS), falls back to ffmpeg region capture with
 *    a stderr warning so the degradation is visible. **Only** [HelperNotBundledException] triggers
 *    fallback — operational SCK failures (Screen Recording permission denied, target window not
 *    found, helper crashed during init) propagate as `IllegalStateException` so the caller sees the
 *    real error rather than getting a silently-different recording.
 * 4. Non-macOS host with a window whose [TitledWindow.title] is non-blank, AND a non-null
 *    [windowsWindowRecorder] — uses [FfmpegWindowRecorder] (gdigrab `title=` capture). This is the
 *    Windows window-targeted path: window movement is followed automatically and occlusion doesn't
 *    matter. Jewel-in-IDE tool windows have no top-level title so they fall through to the region
 *    path (see step 5).
 * 5. Non-macOS host without an applicable [windowsWindowRecorder] OR with a blank/null title, on a
 *    non-Wayland host → ffmpeg region capture. The region path is the documented fallback when the
 *    target window title is missing, ambiguous, or points at a tool window with no top-level title.
 *    The underlying [FfmpegRecorder]'s [FfmpegBackend.detect] picks the platform device: gdigrab on
 *    Windows, x11grab on Linux Xorg. (Linux Wayland is handled by step 1 before falling through
 *    here, and `LinuxX11Grab` itself throws on Wayland — see `FfmpegBackend.checkNotWayland`.)
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
    private val waylandPortalRecorder: Recorder?,
    private val waylandPortalWindowRecorder: WindowRecorder?,
    private val isMacOs: () -> Boolean,
    private val isWindows: () -> Boolean,
    private val isWayland: () -> Boolean,
) {

    constructor(
        sckRecorder: WindowRecorder = ScreenCaptureKitRecorder(),
        ffmpegRecorder: Recorder = FfmpegRecorder(),
        windowsWindowRecorder: WindowRecorder? = defaultWindowsWindowRecorder(),
        waylandPortalRecorder: Recorder? = defaultWaylandPortalRecorder(),
        waylandPortalWindowRecorder: WindowRecorder? = defaultWaylandPortalWindowRecorder(),
    ) : this(
        sckRecorder,
        ffmpegRecorder,
        windowsWindowRecorder,
        waylandPortalRecorder,
        waylandPortalWindowRecorder,
        ::defaultIsMacOs,
        ::defaultIsWindows,
        ::defaultIsWayland,
    )

    fun start(
        window: TitledWindow?,
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
        windowOwnerPid: Long = ProcessHandle.current().pid(),
    ): RecordingHandle {
        // Linux Wayland routing has to happen BEFORE the window-null check below: a Wayland
        // session can't fall through to FfmpegRecorder for either window-targeted OR region
        // capture, because LinuxX11Grab throws on Wayland.
        //
        // Window-targeted (#85) takes precedence when both wired and the caller has a window in
        // mind: SourceType.WINDOW makes the compositor follow the window across the screen and
        // hand us its pixels directly, matching the SCK / gdigrab title= ergonomics on the other
        // OSes. Falls back to the region recorder when no window is supplied OR the window-
        // targeted recorder isn't wired (e.g. older callers, helper construction failed).
        if (isWayland() && window != null && waylandPortalWindowRecorder != null) {
            return waylandPortalWindowRecorder.start(
                window = window,
                windowOwnerPid = windowOwnerPid,
                output = output,
                options = options,
            )
        }
        if (isWayland() && waylandPortalRecorder != null) {
            return waylandPortalRecorder.start(region, output, options)
        }
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

        @JvmStatic
        fun defaultIsLinux(): Boolean =
            System.getProperty("os.name").orEmpty().lowercase().contains("linux")

        /** Wayland session detection — three-tier env+filesystem check, see #77 stage 1. */
        @JvmStatic
        fun defaultIsWayland(): Boolean = defaultIsLinux() && detectWaylandSession(System::getenv)

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

        /**
         * Constructs a [WaylandPortalRecorder] only when the host is Linux. dbus-java + gst-launch
         * are no-ops on macOS / Windows (the gst-launch binary doesn't exist there, the D-Bus
         * session bus isn't running) — gating on `isLinux` keeps cross-host distribution jars from
         * triggering construction-time failures. The recorder is null on non-Linux even if the
         * AutoRecorder is constructed; the router never asks for it on those hosts.
         *
         * Failures resolving the gst-launch path or constructing the recorder are swallowed for the
         * same reasons as [defaultWindowsWindowRecorder]: a Linux host without GStreamer installed
         * should still be able to instantiate AutoRecorder for non-Wayland or non-recording flows.
         * If the user actually triggers Wayland recording on such a host, the construction-time
         * error from `WaylandPortalRecorder.init` would surface at start() time as a clear
         * "gst-launch-1.0 not found, install gstreamer1.0-tools".
         */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun defaultWaylandPortalRecorder(): Recorder? {
            if (!defaultIsLinux()) return null
            return try {
                WaylandPortalRecorder()
            } catch (_: Throwable) {
                null
            }
        }

        /**
         * Constructs a [WaylandPortalWindowRecorder] only when the host is Linux. Same gating as
         * [defaultWaylandPortalRecorder] — the helper binary, GStreamer, and the portal D-Bus
         * service are all Linux-only, so wiring this on macOS / Windows would throw at construction
         * time. Failures resolving dependencies are swallowed for the same reasons.
         */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun defaultWaylandPortalWindowRecorder(): WindowRecorder? {
            if (!defaultIsLinux()) return null
            return try {
                WaylandPortalWindowRecorder()
            } catch (_: Throwable) {
                null
            }
        }
    }
}
