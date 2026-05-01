package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Files
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
         * Three signals checked in order; any one positive returns true:
         * 1. `XDG_SESSION_TYPE=wayland` — set by systemd-logind / GDM for graphical sessions that
         *    started under Wayland.
         * 2. `WAYLAND_DISPLAY` non-blank (typically `wayland-0`) — set when a Wayland compositor
         *    socket is reachable. Catches manually-started compositors and user-namespace setups
         *    where `XDG_SESSION_TYPE` may be missing.
         * 3. A `wayland-*` socket file under `XDG_RUNTIME_DIR` — catches the SSH-into-Wayland-host
         *    case that #1 and #2 miss. SSH's `XDG_SESSION_TYPE` reports `tty` (not `wayland`) and
         *    `WAYLAND_DISPLAY` is unset, but the user's Wayland compositor is still running and
         *    leaves a socket at `/run/user/<uid>/wayland-0`. Without this tier, recording a Wayland
         *    host over SSH would silently produce a black mp4 even though we have ample signal that
         *    Wayland is in play.
         *
         * The pure form (taking `getenv` and a filesystem-probe lambda as parameters rather than
         * reading [System.getenv] / [Files] directly) lets unit tests cover the signal matrix
         * deterministically without mucking with process-level env vars (which the JVM can't modify
         * at runtime anyway) or per-test temp directories.
         */
        @Suppress("ReturnCount")
        internal fun detectWaylandSession(
            getenv: (String) -> String?,
            runtimeDirHasWaylandSocket: (Path) -> Boolean = ::defaultRuntimeDirHasWaylandSocket,
        ): Boolean {
            val sessionType = getenv("XDG_SESSION_TYPE")?.lowercase()
            if (sessionType == "wayland") return true
            val waylandDisplay = getenv("WAYLAND_DISPLAY")
            if (!waylandDisplay.isNullOrBlank()) return true
            val runtimeDir = getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return false
            return runtimeDirHasWaylandSocket(Path.of(runtimeDir))
        }

        /**
         * Default filesystem probe for tier 3 of [detectWaylandSession]. Returns true if
         * [runtimeDir] is a readable directory and contains any entry whose name starts with
         * `wayland-` (matching the compositor's socket file convention).
         *
         * Wrapped in [runCatching] because the directory may exist but be unreadable due to a mount
         * race or permission glitch — in that case we'd rather treat the host as non-Wayland (and
         * let the smoke surface the real failure mode) than throw out of detection entirely.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun defaultRuntimeDirHasWaylandSocket(runtimeDir: Path): Boolean {
            if (!Files.isDirectory(runtimeDir)) return false
            return runCatching {
                    Files.list(runtimeDir).use { stream ->
                        stream.anyMatch { it.fileName.toString().startsWith("wayland-") }
                    }
                }
                .getOrDefault(false)
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
                    "WAYLAND_DISPLAY / XDG_RUNTIME_DIR/wayland-* socket. " +
                    "Use Wayland-native capture instead: construct " +
                    "`dev.sebastiano.spectre.recording.AutoRecorder` (which routes Wayland " +
                    "sessions through xdg-desktop-portal + PipeWire automatically), or " +
                    "instantiate `WaylandPortalRecorder` directly. Alternatively, switch to an " +
                    "Xorg session (set `WaylandEnable=false` in /etc/gdm3/custom.conf and " +
                    "restart gdm, or pick \"Ubuntu on Xorg\" at the GDM login screen), or run " +
                    "under Xvfb."
            )
        }
    }
}
