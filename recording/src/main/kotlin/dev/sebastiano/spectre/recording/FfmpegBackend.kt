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
         * Returns true when capture should use the Linux Wayland/portal path, false for X11/Xvfb.
         *
         * Order of signals (#397 — Xvfb must not be hijacked by residual Wayland env/sockets):
         * 0. `SPECTRE_CAPTURE_BACKEND=x11|wayland` — explicit override (also accepts `xorg` /
         *    `xvfb` → X11, `portal` → Wayland). Use when nested Xvfb probes are unavailable.
         * 1. Active pure-X11 [DISPLAY] (Xvfb / non-XWayland Xorg) — process windows live on that
         *    server; prefer `ximagesrc` even if the login session exported Wayland vars.
         * 2. `XDG_SESSION_TYPE=wayland` — logind/GDM graphical Wayland session.
         * 3. `WAYLAND_DISPLAY` non-blank — compositor socket name for this session.
         * 4. A `wayland-*` socket under `XDG_RUNTIME_DIR` **only when DISPLAY is unset** — SSH into
         *    a Wayland host without X11 forwarding. Residual sockets must not override an active
         *    Xvfb/Xorg DISPLAY (that was the #397 misroute).
         *
         * Real Wayland+XWayland still returns true: pure-X11 probe is false for XWayland, and tiers
         * 2–3 fire. Do not treat "DISPLAY is set" alone as X11 — XWayland always has DISPLAY.
         *
         * Injectable [getenv] / probes keep the matrix unit-testable without process env mutation.
         */
        @Suppress("ReturnCount")
        internal fun detectWaylandSession(
            getenv: (String) -> String?,
            runtimeDirHasWaylandSocket: (Path) -> Boolean = ::defaultRuntimeDirHasWaylandSocket,
            displayIsPureX11: (String) -> Boolean = ::defaultDisplayIsPureX11,
        ): Boolean {
            when (getenv("SPECTRE_CAPTURE_BACKEND")?.trim()?.lowercase()) {
                "x11",
                "xorg",
                "xvfb" -> return false
                "wayland",
                "portal" -> return true
            }
            val display = getenv("DISPLAY")?.takeIf { it.isNotBlank() }
            if (display != null && displayIsPureX11(display)) return false
            val sessionType = getenv("XDG_SESSION_TYPE")?.lowercase()
            if (sessionType == "wayland") return true
            val waylandDisplay = getenv("WAYLAND_DISPLAY")
            if (!waylandDisplay.isNullOrBlank()) return true
            // Tier 4: residual compositor socket. Only when this process has no X11 DISPLAY —
            // otherwise Xvfb-under-SSH would misroute to portal (#397).
            if (display != null) return false
            val runtimeDir = getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return false
            return runtimeDirHasWaylandSocket(Path.of(runtimeDir))
        }

        /**
         * Default filesystem probe for the residual-socket tier of [detectWaylandSession]. Returns
         * true if [runtimeDir] is a readable directory and contains any entry whose name starts
         * with `wayland-` (matching the compositor's socket file convention).
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
         * True when [display] talks to a pure X11 server (Xvfb / Xorg), not XWayland.
         *
         * Used so `xvfb-run` on a host that still exports Wayland session vars routes capture to
         * X11. Prefer an Xvfb process match for [display]; fall back to `xdpyinfo` and treat the
         * presence of the `XWAYLAND` extension as "not pure X11". Probe failures return false (do
         * not claim pure X11 without evidence).
         */
        @Suppress("TooGenericExceptionCaught")
        internal fun defaultDisplayIsPureX11(display: String): Boolean {
            if (linuxDisplayMatchesXvfbProcess(display)) return true
            return runCatching { xdpyinfoReportsPureX11(display) }.getOrDefault(false)
        }

        /**
         * Scans Linux `/proc/<pid>/cmdline` entries for an `Xvfb` process serving [display] (e.g.
         * `:99`). Returns false on other OSes or when `/proc` is unavailable.
         */
        @Suppress("TooGenericExceptionCaught")
        internal fun linuxDisplayMatchesXvfbProcess(display: String): Boolean {
            val displayToken = normalizeDisplayToken(display) ?: return false
            val proc = Path.of("/proc")
            if (!Files.isDirectory(proc)) return false
            return runCatching {
                    Files.list(proc).use { stream ->
                        stream.anyMatch { entry ->
                            val name = entry.fileName.toString()
                            if (name.toLongOrNull() == null) return@anyMatch false
                            val cmdline =
                                runCatching { Files.readAllBytes(entry.resolve("cmdline")) }
                                    .getOrNull() ?: return@anyMatch false
                            val args =
                                cmdline.toString(Charsets.UTF_8).split('\u0000').filter {
                                    it.isNotEmpty()
                                }
                            cmdlineMatchesXvfbDisplay(args, displayToken)
                        }
                    }
                }
                .getOrDefault(false)
        }

        /**
         * True when [args] is an Xvfb argv that serves [displayToken] (normalized, e.g. `:99`).
         * Non-display args (binary path, `-screen`, geometry) are skipped — must not abort the scan
         * when `normalizeDisplayToken` returns null.
         */
        internal fun cmdlineMatchesXvfbDisplay(args: List<String>, displayToken: String): Boolean {
            if (args.none { it == "Xvfb" || it.endsWith("/Xvfb") }) return false
            return args.any { normalizeDisplayToken(it) == displayToken }
        }

        /** `:99.0` / `host:99` → `:99` for comparison with Xvfb argv. */
        internal fun normalizeDisplayToken(raw: String): String? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            val afterHost =
                when {
                    trimmed.startsWith(":") -> trimmed
                    trimmed.contains(':') -> trimmed.substringAfterLast(':').let { ":$it" }
                    else -> return null
                }
            val num =
                afterHost.removePrefix(":").substringBefore('.').takeIf { it.isNotEmpty() }
                    ?: return null
            if (num.toIntOrNull() == null) return null
            return ":$num"
        }

        /**
         * Queries `xdpyinfo` for [display]; true when the server responds without advertising the
         * `XWAYLAND` extension. Bounded by [XDPYINFO_TIMEOUT_MS] including output drain — never
         * blocks forever on a hung X connection.
         */
        @Suppress("TooGenericExceptionCaught")
        private fun xdpyinfoReportsPureX11(display: String): Boolean {
            val process =
                ProcessBuilder("xdpyinfo", "-display", display).redirectErrorStream(true).start()
            return try {
                val outputRef = java.util.concurrent.atomic.AtomicReference("")
                val reader =
                    Thread(
                            {
                                runCatching {
                                    outputRef.set(
                                        process.inputStream.bufferedReader(Charsets.UTF_8).use {
                                            it.readText()
                                        }
                                    )
                                }
                            },
                            "spectre-xdpyinfo-reader",
                        )
                        .apply {
                            isDaemon = true
                            start()
                        }
                val finished =
                    process.waitFor(XDPYINFO_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    reader.join(READER_JOIN_MS)
                    return false
                }
                reader.join(READER_JOIN_MS)
                if (process.exitValue() != 0) return false
                // XWayland advertises an "XWAYLAND" extension; Xvfb/Xorg do not.
                !outputRef.get().contains("XWAYLAND", ignoreCase = true)
            } catch (_: Exception) {
                process.destroyForcibly()
                false
            }
        }

        private const val XDPYINFO_TIMEOUT_MS: Long = 3_000
        private const val READER_JOIN_MS: Long = 500

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
                    "WAYLAND_DISPLAY / residual wayland-* socket (only when DISPLAY is unset). " +
                    "Use Wayland-native capture instead: construct " +
                    "`dev.sebastiano.spectre.recording.AutoRecorder` (which routes Wayland " +
                    "sessions through xdg-desktop-portal + PipeWire automatically), or " +
                    "instantiate `WaylandPortalRecorder` directly. Alternatively, switch to an " +
                    "Xorg session (set `WaylandEnable=false` in /etc/gdm3/custom.conf and " +
                    "restart gdm, or pick \"Ubuntu on Xorg\" at the GDM login screen), run " +
                    "under Xvfb, or set SPECTRE_CAPTURE_BACKEND=x11 for nested Xvfb only."
            )
        }
    }
}
