package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Path

/**
 * Picks the platform-appropriate `ffmpeg` argv builder for [FfmpegRecorder].
 *
 * Each backend wraps the native capture device that ffmpeg exposes on its host OS:
 * - [MacOsAvfoundation] — `-f avfoundation` with crop-filter region selection. Requires the macOS
 *   Screen Recording permission.
 * - [WindowsGdigrab] — `-f gdigrab` with input-side `-offset_x`/`-offset_y`/`-video_size` region
 *   selection. No equivalent of macOS's TCC permission gate, but the window must be visible.
 * - [LinuxX11Grab] — `-f x11grab` with input-side `-video_size` and the `<display>+x,y` URL form
 *   for region selection. Reads the `DISPLAY` env var at argv build time, falling back to `:0.0`.
 *   **Xorg sessions only**: on Wayland-with-XWayland, ffmpeg's x11grab succeeds without erroring
 *   but produces uniform-black frames because Wayland's security model blocks framebuffer reads by
 *   other clients. [LinuxX11Grab] detects Wayland via env vars and throws an explicit error rather
 *   than produce silent garbage — see [detectWaylandSession]. Native Wayland capture (PipeWire +
 *   xdg-desktop-portal) is tracked as [#77](https://github.com/rock3r/spectre/issues/77).
 *
 * The backend is selected at [FfmpegRecorder] construction time via [detect], which inspects
 * `os.name` (the same approach the rest of the module uses — see `MacOsRecordingPermissions`).
 * Tests inject a specific backend directly so the produced argv is deterministic regardless of the
 * host OS.
 */
internal sealed interface FfmpegBackend {

    /** Builds the ffmpeg argv for a region capture in this backend's coordinate space. */
    fun buildRegionArgv(
        ffmpegPath: Path,
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): List<String>

    object MacOsAvfoundation : FfmpegBackend {
        override fun buildRegionArgv(
            ffmpegPath: Path,
            region: Rectangle,
            output: Path,
            options: RecordingOptions,
        ): List<String> = FfmpegCli.avfoundationRegionCapture(ffmpegPath, region, output, options)
    }

    object WindowsGdigrab : FfmpegBackend {
        override fun buildRegionArgv(
            ffmpegPath: Path,
            region: Rectangle,
            output: Path,
            options: RecordingOptions,
        ): List<String> = FfmpegCli.gdigrabRegionCapture(ffmpegPath, region, output, options)
    }

    object LinuxX11Grab : FfmpegBackend {
        override fun buildRegionArgv(
            ffmpegPath: Path,
            region: Rectangle,
            output: Path,
            options: RecordingOptions,
        ): List<String> {
            // Wayland sessions silently produce black frames via x11grab — Wayland's security
            // model blocks framebuffer reads by clients that aren't the compositor. Detect and
            // throw a clear error here rather than spawn ffmpeg and watch it write a useless
            // mp4 of pure-black pixels (see #77 for measurement notes from the dev VM).
            checkNotWayland(System::getenv)
            // X11's display name is conventionally read from the `DISPLAY` env var. Most desktop
            // sessions set it (`:0`, `:0.0`, `:1`, etc.); over SSH-without-X-forwarding it's
            // unset. Fall back to `:0.0` so a misconfigured environment produces a clear
            // ffmpeg-side "cannot open display" error rather than a confusing argv NPE.
            val display = System.getenv("DISPLAY")?.takeIf { it.isNotBlank() } ?: ":0.0"
            return FfmpegCli.x11grabRegionCapture(ffmpegPath, region, output, options, display)
        }
    }

    companion object {

        /**
         * Resolves the backend for the current OS. macOS → [MacOsAvfoundation]; Windows →
         * [WindowsGdigrab]; Linux → [LinuxX11Grab]. Any other host (BSD, Solaris) throws
         * [UnsupportedOperationException] with a message naming the OS.
         *
         * Linux's selection is Xorg-only at runtime: [LinuxX11Grab.buildRegionArgv] checks for a
         * Wayland session and throws if it sees one. Wayland-native capture (PipeWire +
         * xdg-desktop-portal) is a separate backend tracked under
         * <https://github.com/rock3r/spectre/issues/77>.
         */
        fun detect(): FfmpegBackend {
            val osName = System.getProperty("os.name").orEmpty()
            return when {
                osName.lowercase().contains("mac") -> MacOsAvfoundation
                osName.lowercase().contains("windows") -> WindowsGdigrab
                osName.lowercase().contains("linux") -> LinuxX11Grab
                else ->
                    throw UnsupportedOperationException(
                        "FfmpegRecorder has no backend for os.name=\"$osName\". " +
                            "Supported: macOS (avfoundation), Windows (gdigrab), Linux Xorg " +
                            "(x11grab). Wayland: see https://github.com/rock3r/spectre/issues/77."
                    )
            }
        }

        /**
         * Returns true when the host is running a Wayland session, false otherwise.
         *
         * Two signals, either of which is sufficient:
         * - `XDG_SESSION_TYPE=wayland` — set by systemd-logind / GDM for Wayland sessions.
         * - `WAYLAND_DISPLAY` — set to a non-blank value (typically `wayland-0`) when a Wayland
         *   compositor is reachable. This catches the edge case where `XDG_SESSION_TYPE` is unset
         *   but a Wayland compositor is running anyway (manually-started compositors,
         *   user-namespace setups).
         *
         * The pure form (taking `getenv` as a parameter rather than reading [System.getenv]
         * directly) lets unit tests cover the signal matrix deterministically without mucking with
         * process-level env vars (which the JVM doesn't even support modifying at runtime).
         */
        @Suppress("ReturnCount")
        internal fun detectWaylandSession(getenv: (String) -> String?): Boolean {
            val sessionType = getenv("XDG_SESSION_TYPE")?.lowercase()
            if (sessionType == "wayland") return true
            val waylandDisplay = getenv("WAYLAND_DISPLAY")
            if (!waylandDisplay.isNullOrBlank()) return true
            return false
        }

        /**
         * Throws [UnsupportedOperationException] if [getenv] reports a Wayland session.
         *
         * Internal so tests can drive it with a fake [getenv]. The real call site is
         * [LinuxX11Grab.buildRegionArgv].
         */
        internal fun checkNotWayland(getenv: (String) -> String?) {
            if (!detectWaylandSession(getenv)) return
            throw UnsupportedOperationException(
                "ffmpeg's x11grab silently captures black frames on Wayland sessions even with " +
                    "XWayland in the loop — Wayland's security model blocks framebuffer reads " +
                    "by clients other than the compositor. Detected via XDG_SESSION_TYPE / " +
                    "WAYLAND_DISPLAY. " +
                    "Workarounds: switch to an Xorg session (set `WaylandEnable=false` in " +
                    "/etc/gdm3/custom.conf and restart gdm, or pick \"Ubuntu on Xorg\" at the " +
                    "GDM login screen), or run under Xvfb. " +
                    "Wayland-native capture (PipeWire + xdg-desktop-portal) is tracked under " +
                    "https://github.com/rock3r/spectre/issues/77."
            )
        }
    }
}
