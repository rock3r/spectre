package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.FfmpegBackend.Companion.detectWaylandSession
import dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder
import dev.sebastiano.spectre.recording.portal.WaylandPortalWindowRecorder
import dev.sebastiano.spectre.recording.portal.WaylandWindowSourceRecorder
import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.awt.Rectangle
import java.lang.ProcessHandle
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * High-level recorder that picks between window-targeted capture and region-based ffmpeg capture
 * per call, so callers don't have to know which backend is appropriate.
 *
 * Routing logic (in order):
 * 1. Linux Wayland session (detected via env / runtime-dir signals; see
 *    [FfmpegBackend.detectWaylandSession]) — handled BEFORE the window-null check below because
 *    `LinuxX11Grab` throws on Wayland and can't be a fallback. Sub-routing:
 *     - `window != null` AND [waylandPortalWindowRecorder] wired (#85) → window-targeted portal
 *       recorder. Uses `SourceType.WINDOW`: the dialog asks the user to pick a specific window and
 *       the granted PipeWire stream contains only that window's pixels (no leakage from occluding
 *       apps). The helper still crops to the JVM-supplied `region` (the window's bounds at start),
 *       so the mp4 dimensions match the window's pixel size.
 *     - Otherwise (`window == null`, OR no window-targeted recorder wired) AND
 *       [waylandPortalRecorder] wired → region-targeted portal recorder. Uses `SourceType.MONITOR`:
 *       the dialog asks the user to pick a monitor, and the helper crops the monitor stream to
 *       [region]. This is the embedded `ComposePanel` path and the backwards-compatible fallback
 *       for callers from before #85.
 *     - Neither portal recorder wired → falls through to ffmpeg, which fails fast on Wayland. Both
 *       portal paths pop a compositor permission dialog on first call within a session and reuse
 *       the grant via the portal's `restore_token` afterwards. See [WaylandPortalRecorder].
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
public class AutoRecorder
internal constructor(
    private val sckRecorder: WindowRecorder,
    private val ffmpegRecorder: Recorder,
    private val windowsWindowRecorder: WindowRecorder?,
    private val waylandPortalRecorder: Recorder?,
    private val waylandPortalWindowRecorder: WaylandWindowSourceRecorder?,
    private val waylandPortalRecorderFailure: Throwable?,
    private val waylandPortalWindowRecorderFailure: Throwable?,
    private val isMacOs: () -> Boolean,
    private val isWindows: () -> Boolean,
    private val isWayland: () -> Boolean,
) {

    public constructor(
        sckRecorder: WindowRecorder = ScreenCaptureKitRecorder(),
        ffmpegRecorder: Recorder = FfmpegRecorder(),
        windowsWindowRecorder: WindowRecorder? = defaultWindowsWindowRecorder(),
        waylandPortalRecorder: Recorder? = defaultPortals.region,
        waylandPortalWindowRecorder: WaylandWindowSourceRecorder? = defaultPortals.window,
    ) : this(
        sckRecorder = sckRecorder,
        ffmpegRecorder = ffmpegRecorder,
        windowsWindowRecorder = windowsWindowRecorder,
        waylandPortalRecorder = waylandPortalRecorder,
        waylandPortalWindowRecorder = waylandPortalWindowRecorder,
        // A user who passed a non-null recorder owns its construction; we only surface our own
        // resolution failure for slots the caller left as null (i.e. accepted the default).
        waylandPortalRecorderFailure =
            if (waylandPortalRecorder == null) defaultPortals.regionFailure else null,
        waylandPortalWindowRecorderFailure =
            if (waylandPortalWindowRecorder == null) defaultPortals.windowFailure else null,
        isMacOs = ::defaultIsMacOs,
        isWindows = ::defaultIsWindows,
        isWayland = ::defaultIsWayland,
    )

    /**
     * Latched to true the first time we emit the Wayland portal fallback warning, so repeated
     * `start()` calls on the same AutoRecorder instance don't spam stderr.
     */
    private val waylandFallbackWarned = AtomicBoolean(false)

    public fun start(
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
        // Window-targeted (#85) takes precedence when both wired and the caller has a window
        // in mind: SourceType.WINDOW makes the portal scope the granted stream to the picked
        // window's pixels (no leakage from occluding apps the way MONITOR + region capture
        // suffers from). Falls back to the region recorder when no window is supplied OR the
        // window-targeted recorder isn't wired (e.g. older callers, helper construction
        // failed). Both pass the same `region` to the helper; the difference is the
        // SourceType the portal hands to the compositor.
        if (isWayland() && window != null && waylandPortalWindowRecorder != null) {
            return waylandPortalWindowRecorder.start(window, region, output, options)
        }
        if (isWayland() && waylandPortalRecorder != null) {
            return waylandPortalRecorder.start(region, output, options)
        }
        if (isWayland()) {
            // Wayland session detected but no portal recorder could be constructed. The
            // ffmpeg fallback below will fail loudly ("Wayland detected, x11grab unavailable")
            // — but the underlying construction failure for the portal recorder is the more
            // useful diagnostic, so surface it once via stderr before we let ffmpeg complete
            // the routing.
            maybeWarnWaylandFallback(window)
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

    /**
     * Emit the Wayland portal fallback warning at most once per AutoRecorder instance. Picks the
     * window-recorder failure when a [window] is supplied (that's the slot the router checked
     * first), otherwise the region-recorder failure. Both being null means the caller explicitly
     * passed `null` recorders without going through our defaults — we still emit the warning so the
     * silent downgrade is visible, just with a less specific cause.
     */
    private fun maybeWarnWaylandFallback(window: TitledWindow?) {
        if (!waylandFallbackWarned.compareAndSet(false, true)) return
        val failure =
            if (window != null) {
                waylandPortalWindowRecorderFailure ?: waylandPortalRecorderFailure
            } else {
                waylandPortalRecorderFailure ?: waylandPortalWindowRecorderFailure
            }
        val cause =
            failure?.message?.takeIf { it.isNotBlank() }
                ?: failure?.javaClass?.simpleName
                ?: "no construction failure recorded — recorder slot was passed as null"
        System.err.println(
            "AutoRecorder: Wayland portal recorder unavailable ($cause); falling back to " +
                "ffmpeg region capture (which will fail on Wayland — install the portal helper " +
                "prerequisites or supply a custom recorder)."
        )
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
         * Lazily resolves both portal recorders once per JVM (the first time the public no-arg
         * `AutoRecorder` constructor's defaults fire). Captures any construction failure so the
         * router can surface it via the fallback warning instead of silently dropping it.
         *
         * Tests bypass this entirely by going through the internal constructor.
         */
        val defaultPortals: WaylandPortalRecorders by lazy {
            WaylandPortalRecorders.resolveDefaults(::defaultIsLinux)
        }
    }
}

/**
 * Internal routing state for the Wayland portal recorders + the construction failures (if any) that
 * produced their null slots. Stays out of the public API so `AutoRecorder`'s public constructor
 * doesn't have to take `Throwable` parameters.
 */
internal data class WaylandPortalRecorders(
    val region: Recorder?,
    val window: WaylandWindowSourceRecorder?,
    val regionFailure: Throwable?,
    val windowFailure: Throwable?,
) {

    companion object {

        /**
         * Constructs the default portal recorders on Linux; returns all-null on other hosts.
         *
         * dbus-java + gst-launch are no-ops on macOS / Windows (the gst-launch binary doesn't exist
         * there, the D-Bus session bus isn't running) — gating on `isLinux` keeps cross-host
         * distribution jars from triggering construction-time failures. On Linux, failures
         * resolving the gst-launch path or constructing either recorder are captured rather than
         * dropped, so a Linux host without GStreamer installed can still instantiate AutoRecorder
         * for non-Wayland flows but a user who triggers Wayland recording sees the diagnostic via
         * the stderr fallback warning.
         */
        @Suppress("TooGenericExceptionCaught")
        fun resolveDefaults(isLinux: () -> Boolean): WaylandPortalRecorders {
            if (!isLinux()) return WaylandPortalRecorders(null, null, null, null)
            var region: Recorder? = null
            var regionFailure: Throwable? = null
            try {
                region = WaylandPortalRecorder()
            } catch (t: Throwable) {
                regionFailure = t
            }
            var window: WaylandWindowSourceRecorder? = null
            var windowFailure: Throwable? = null
            try {
                window = WaylandPortalWindowRecorder()
            } catch (t: Throwable) {
                windowFailure = t
            }
            return WaylandPortalRecorders(region, window, regionFailure, windowFailure)
        }
    }
}
