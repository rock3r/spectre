package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.WaylandPortalRecorder
import dev.sebastiano.spectre.recording.portal.WaylandPortalWindowRecorder
import dev.sebastiano.spectre.recording.portal.WaylandWindowSourceRecorder
import dev.sebastiano.spectre.recording.screencapturekit.HelperNotBundledException
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureKitRecorder
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import dev.sebastiano.spectre.recording.windows.WindowsGraphicsCaptureHelperNotBundledException
import dev.sebastiano.spectre.recording.windows.WindowsGraphicsCaptureRecorder
import java.awt.Rectangle
import java.lang.ProcessHandle
import java.nio.file.Path

/**
 * High-level recorder with explicit capture modes.
 *
 * [startWindow] uses window-scoped backends only: ScreenCaptureKit on macOS, Windows Graphics
 * Capture on Windows, the Linux helper's Xorg/Xvfb named-window path, and the Wayland portal
 * window-source path on Linux Wayland. If the requested window mode is unavailable, it throws with
 * an actionable error instead of silently falling back to region capture.
 *
 * [startRegion] uses region capture only: ScreenCaptureKit on macOS, Windows Graphics Capture on
 * Windows, the Linux helper's Xorg/Xvfb path, and the Wayland portal monitor-source path on Linux
 * Wayland. If the requested region mode is unavailable, it throws instead of switching capture
 * semantics.
 */
public class AutoRecorder
internal constructor(
    private val sckRecorder: WindowRecorder,
    private val macOsRegionRecorder: Recorder,
    private val windowsWindowRecorder: WindowRecorder?,
    private val windowsRegionRecorder: Recorder?,
    private val linuxRegionRecorder: Recorder?,
    private val linuxWindowRecorder: WindowRecorder?,
    private val waylandPortalRecorder: Recorder?,
    private val waylandPortalWindowRecorder: WaylandWindowSourceRecorder?,
    private val waylandPortalRecorderFailure: Throwable?,
    private val waylandPortalWindowRecorderFailure: Throwable?,
    private val isMacOs: () -> Boolean,
    private val isWindows: () -> Boolean,
    private val isLinux: () -> Boolean,
    private val isWayland: () -> Boolean,
) {

    public constructor(
        sckRecorder: WindowRecorder = ScreenCaptureKitRecorder(),
        macOsRegionRecorder: Recorder = sckRecorder as? Recorder ?: ScreenCaptureKitRecorder(),
        windowsWindowRecorder: WindowRecorder? = defaultWindowsWindowRecorder(),
        windowsRegionRecorder: Recorder? = defaultWindowsRegionRecorder(),
        waylandPortalRecorder: Recorder? = defaultPortals.region,
        waylandPortalWindowRecorder: WaylandWindowSourceRecorder? = defaultPortals.window,
    ) : this(
        sckRecorder = sckRecorder,
        macOsRegionRecorder = macOsRegionRecorder,
        windowsWindowRecorder = windowsWindowRecorder,
        windowsRegionRecorder = windowsRegionRecorder,
        linuxRegionRecorder = defaultLinuxRecorder,
        linuxWindowRecorder = defaultLinuxRecorder,
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
        isLinux = ::defaultIsLinux,
        isWayland = ::defaultIsWayland,
    )

    public fun startWindow(
        window: TitledWindow,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
        windowOwnerPid: Long = ProcessHandle.current().pid(),
    ): RecordingHandle =
        startWindow(
            window = window,
            output = output,
            options = options,
            windowOwnerPid = windowOwnerPid,
            cropInWindow = null,
            scaleX = 1.0,
            scaleY = 1.0,
        )

    /**
     * Window-targeted capture, optionally cropped to an embedded surface.
     *
     * When [cropInWindow] is non-null, captures the host top-level [window] and crops to that
     * rectangle (AWT user space relative to the window top-left — same as window-identity
     * `surfaceBoundsInWindow`). This is the preferred path for handle-less Compose surfaces (spike
     * constraint #5 / #186). Prefer this over [startRegion], which is occlusion-sensitive and does
     * not follow the window.
     *
     * [scaleX]/[scaleY] come from `GraphicsConfiguration.defaultTransform` (window-identity
     * snapshot). They convert AWT user-space crop to device pixels on Windows; macOS uses points
     * and applies the window's backing scale inside the helper.
     *
     * **v1:** crop is fixed at start. Linux Wayland/X11 do not yet implement window+crop; callers
     * get an actionable error rather than a silent region fallback.
     */
    public fun startWindow(
        window: TitledWindow,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
        windowOwnerPid: Long = ProcessHandle.current().pid(),
        cropInWindow: Rectangle?,
        scaleX: Double = 1.0,
        scaleY: Double = 1.0,
    ): RecordingHandle {
        if (cropInWindow != null) {
            return startWindowCropped(
                window,
                cropInWindow,
                output,
                options,
                windowOwnerPid,
                scaleX,
                scaleY,
            )
        }
        if (isWayland()) {
            val recorder =
                waylandPortalWindowRecorder
                    ?: throw unavailable(
                        "Wayland window capture",
                        waylandPortalWindowRecorderFailure,
                    )
            return recorder.start(window, window.bounds, output, options)
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
                throw unavailable("macOS window capture", e)
            }
        }
        val title = window.title
        val recorder = windowsWindowRecorder
        if (isWindows()) {
            require(!title.isNullOrBlank()) {
                "AutoRecorder.startWindow requires a non-blank window title on Windows. " +
                    "Use startRegion(...) explicitly if region capture is what you want."
            }
            return recorder?.start(window, windowOwnerPid, output, options)
                ?: throw unavailable("Windows window capture", null)
        }
        if (isLinux()) {
            require(!title.isNullOrBlank()) {
                "AutoRecorder.startWindow requires a non-blank window title on Linux X11. " +
                    "Use startRegion(...) explicitly if region capture is what you want."
            }
            return linuxWindowRecorder?.start(window, windowOwnerPid, output, options)
                ?: throw unavailable("Linux X11 window capture", null)
        }
        throw unsupportedWindowCapture()
    }

    private fun startWindowCropped(
        window: TitledWindow,
        cropInWindow: Rectangle,
        output: Path,
        options: RecordingOptions,
        windowOwnerPid: Long,
        scaleX: Double,
        scaleY: Double,
    ): RecordingHandle {
        require(cropInWindow.width > 0 && cropInWindow.height > 0) {
            "cropInWindow must be non-empty; got $cropInWindow"
        }
        require(cropInWindow.x >= 0 && cropInWindow.y >= 0) {
            "cropInWindow origin must be non-negative; got $cropInWindow"
        }
        if (isMacOs()) {
            return try {
                sckRecorder.startCropped(
                    window = window,
                    cropInWindow = cropInWindow,
                    windowOwnerPid = windowOwnerPid,
                    output = output,
                    options = options,
                    scaleX = scaleX,
                    scaleY = scaleY,
                )
            } catch (e: HelperNotBundledException) {
                throw unavailable("macOS window+crop capture", e)
            }
        }
        if (isWindows()) {
            val title = window.title
            require(!title.isNullOrBlank()) {
                "AutoRecorder.startWindow (cropped) requires a non-blank window title on Windows."
            }
            val recorder =
                windowsWindowRecorder ?: throw unavailable("Windows window+crop capture", null)
            return recorder.startCropped(
                window = window,
                cropInWindow = cropInWindow,
                windowOwnerPid = windowOwnerPid,
                output = output,
                options = options,
                scaleX = scaleX,
                scaleY = scaleY,
            )
        }
        // Linux parity evaluation (#186): X11 ximagesrc can grab a named window but has no
        // capture-side surface crop that keeps occlusion immunity; Wayland portal window streams
        // could videocrop stream-relative rects but that path is not wired yet. Fail loud rather
        // than silently degrading to region capture (region is the explicit last resort).
        error(
            "Window capture + crop is not available on this Linux session. " +
                "macOS (ScreenCaptureKit sourceRect) and Windows (WGC pre-encode crop) support " +
                "it; Linux X11/Wayland parity is not implemented in v1. Use startRegion(...) " +
                "only as an explicit last resort (occlusion-sensitive, fixed screen rect)."
        )
    }

    public fun startRegion(
        region: Rectangle,
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle {
        if (isWayland()) {
            val recorder =
                waylandPortalRecorder
                    ?: throw unavailable("Wayland region capture", waylandPortalRecorderFailure)
            return recorder.start(region, output, options)
        }
        if (isWindows()) {
            val recorder =
                windowsRegionRecorder ?: throw unavailable("Windows region capture", null)
            return try {
                recorder.start(region, output, options)
            } catch (e: WindowsGraphicsCaptureHelperNotBundledException) {
                throw unavailable("Windows region capture", e)
            }
        }
        if (isMacOs()) {
            return try {
                macOsRegionRecorder.start(region, output, options)
            } catch (e: HelperNotBundledException) {
                throw unavailable("macOS region capture", e)
            }
        }
        if (isLinux()) {
            return linuxRegionRecorder?.start(region, output, options)
                ?: throw unavailable("Linux X11 region capture", null)
        }
        throw unsupportedRegionCapture()
    }

    private fun unavailable(mode: String, cause: Throwable?): IllegalStateException {
        val detail = cause?.message?.takeIf { it.isNotBlank() } ?: cause?.javaClass?.simpleName
        val message =
            if (detail == null) "$mode is unavailable." else "$mode is unavailable: $detail"
        return IllegalStateException(message, cause)
    }

    private fun unsupportedWindowCapture(): IllegalStateException =
        IllegalStateException(
            "AutoRecorder.startWindow is unsupported on this platform because no true " +
                "window-targeted recorder is available. Use startRegion(...) explicitly for " +
                "region capture."
        )

    private fun unsupportedRegionCapture(): IllegalStateException =
        IllegalStateException(
            "AutoRecorder.startRegion is unsupported on this platform because no region recorder " +
                "is available."
        )

    private companion object {
        @JvmStatic fun defaultIsMacOs(): Boolean = HostPlatform.isMacOs()

        @JvmStatic fun defaultIsWindows(): Boolean = HostPlatform.isWindows()

        @JvmStatic fun defaultIsLinux(): Boolean = HostPlatform.isLinux()

        /** Wayland session detection — three-tier env+filesystem check, see #77 stage 1. */
        @JvmStatic fun defaultIsWayland(): Boolean = HostPlatform.isWayland()

        /**
         * Constructs a [WindowsGraphicsCaptureRecorder] only when the host is Windows. On other
         * hosts the public [AutoRecorder] constructor still works — the Windows recorder slots stay
         * null and the router falls through to the host's non-Windows routes.
         *
         * Construction is deliberately lightweight: helper extraction happens at first `start()`,
         * so callers on a Windows host can still instantiate [AutoRecorder] for region capture or
         * API discovery even when the optional `spectre-recording-windows` runtime artifact is not
         * on the classpath.
         */
        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun defaultWindowsWindowRecorder(): WindowRecorder? {
            if (!defaultIsWindows()) return null
            return try {
                defaultWindowsRecorder
            } catch (_: Throwable) {
                null
            }
        }

        @JvmStatic
        @Suppress("TooGenericExceptionCaught")
        fun defaultWindowsRegionRecorder(): Recorder? {
            if (!defaultIsWindows()) return null
            return try {
                defaultWindowsRecorder
            } catch (_: Throwable) {
                null
            }
        }

        val defaultWindowsRecorder: WindowsGraphicsCaptureRecorder by lazy {
            WindowsGraphicsCaptureRecorder()
        }

        val defaultLinuxRecorder: LinuxX11Recorder? by lazy {
            if (defaultIsLinux()) LinuxX11Recorder() else null
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
