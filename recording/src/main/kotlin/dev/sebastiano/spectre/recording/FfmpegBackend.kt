package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

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
         * Order of signals (#397, #575 — an X11 DISPLAY must not be hijacked by residual or
         * inherited Wayland env/sockets):
         * 0. `SPECTRE_CAPTURE_BACKEND=x11|wayland` — explicit override (also accepts `xorg` /
         *    `xvfb` → X11, `portal` → Wayland). Use when the probes below cannot decide.
         * 1. Without [DISPLAY]: `XDG_SESSION_TYPE=wayland` or a non-blank `WAYLAND_DISPLAY` means
         *    Wayland. Otherwise a `wayland-*` socket under `XDG_RUNTIME_DIR` does — SSH into a
         *    Wayland host without X11 forwarding.
         * 2. With [DISPLAY] served by an Xwayland process: Wayland, with or without Wayland env
         *    vars. SSH into a Wayland seat sets only `DISPLAY` and `XDG_SESSION_TYPE=tty`, and
         *    x11grab of Xwayland is black (#607).
         * 3. With [DISPLAY] and no Wayland env var: X11. Residual sockets must not override an
         *    active Xvfb/Xorg DISPLAY (that was the #397 misroute).
         * 4. With [DISPLAY] and a Wayland env var, the env var needs evidence (#575):
         *     - a pure X11 server (Xvfb / Xvnc / Xtigervnc / Xephyr / Xorg process, or `xdpyinfo`
         *       without `XWAYLAND`) → X11;
         *     - otherwise Wayland only if the `WAYLAND_DISPLAY` socket (default `wayland-0`,
         *       relative to `XDG_RUNTIME_DIR` unless absolute) exists. An inherited variable whose
         *       socket is missing, as in a container or nested Xvfb, leaves DISPLAY in charge.
         *
         * Do not treat "DISPLAY is set" alone as X11 — XWayland always has DISPLAY.
         *
         * Injectable [getenv] / probes keep the matrix unit-testable without process env mutation.
         */
        @Suppress("ReturnCount")
        internal fun detectWaylandSession(
            getenv: (String) -> String?,
            runtimeDirHasWaylandSocket: (Path) -> Boolean = ::defaultRuntimeDirHasWaylandSocket,
            displayIsPureX11: (String) -> Boolean = ::defaultDisplayIsPureX11,
            displayIsXwayland: (String) -> Boolean = ::defaultDisplayIsXwayland,
            waylandSocketExists: (Path) -> Boolean = ::isWaylandSocket,
        ): Boolean {
            when (getenv("SPECTRE_CAPTURE_BACKEND")?.trim()?.lowercase()) {
                "x11",
                "xorg",
                "xvfb" -> return false
                "wayland",
                "portal" -> return true
            }
            val display = getenv("DISPLAY")?.takeIf { it.isNotBlank() }
            val sessionIsWayland = getenv("XDG_SESSION_TYPE")?.lowercase() == "wayland"
            val waylandDisplay = getenv("WAYLAND_DISPLAY")?.takeIf { it.isNotBlank() }
            if (display == null) {
                if (sessionIsWayland || waylandDisplay != null) return true
                val runtimeDir =
                    getenv("XDG_RUNTIME_DIR")?.takeIf { it.isNotBlank() } ?: return false
                return runtimeDirHasWaylandSocket(Path.of(runtimeDir))
            }
            if (displayIsXwayland(display)) return true
            if (!sessionIsWayland && waylandDisplay == null) return false
            if (displayIsPureX11(display)) return false
            val socket =
                resolveWaylandSocket(waylandDisplay, getenv("XDG_RUNTIME_DIR")) ?: return false
            return runCatching { waylandSocketExists(socket) }.getOrDefault(false)
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
         * Resolves the compositor socket the way libwayland does: an absolute [waylandDisplay] is
         * used as is, a relative one (default `wayland-0`) lives under [runtimeDir]. Null when it
         * cannot be resolved, which libwayland would also fail to connect to.
         */
        internal fun resolveWaylandSocket(waylandDisplay: String?, runtimeDir: String?): Path? {
            val raw = waylandDisplay ?: DEFAULT_WAYLAND_DISPLAY
            val name = runCatching { Path.of(raw) }.getOrNull()
            if (name == null || name.isAbsolute) return name
            val dir = runtimeDir?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { Path.of(dir).resolve(name) }.getOrNull()
        }

        /** True when [path] exists and is a socket (or other special file), not a file or dir. */
        internal fun isWaylandSocket(path: Path): Boolean {
            val attributes = runCatching {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            }
            return attributes.getOrNull()?.isOther == true
        }

        /** True when [display] is served by an Xwayland process visible in `/proc`. */
        internal fun defaultDisplayIsXwayland(display: String): Boolean =
            isXwaylandServer(linuxDisplayServerName(display))

        /**
         * True when [display] talks to a pure X11 server, not XWayland.
         *
         * Used so `xvfb-run` on a host that still exports Wayland session vars routes capture to
         * X11. Prefer a visible Xvfb / Xvnc / Xtigervnc / Xephyr / Xorg process serving [display];
         * fall back to `xdpyinfo` and treat the presence of the `XWAYLAND` extension as "not pure
         * X11". Probe failures return false (do not claim pure X11 without evidence).
         */
        internal fun defaultDisplayIsPureX11(display: String): Boolean {
            if (isPureX11Server(linuxDisplayServerName(display))) return true
            return runCatching { xdpyinfoReportsPureX11(display) }.getOrDefault(false)
        }

        internal fun isXwaylandServer(serverName: String?): Boolean = serverName == XWAYLAND_SERVER

        internal fun isPureX11Server(serverName: String?): Boolean = serverName in PURE_X11_SERVERS

        /**
         * Scans Linux `/proc/<pid>/cmdline` entries for the X server serving [display] (e.g.
         * `:99`). Returns null on other OSes, when `/proc` is unavailable, for a remote [display],
         * or when no known X server for [display] is visible (for example with `hidepid=2` or
         * another PID namespace).
         */
        internal fun linuxDisplayServerName(display: String): String? {
            val displayToken = localDisplayToken(display) ?: return null
            val proc = Path.of("/proc")
            if (!Files.isDirectory(proc)) return null
            return runCatching {
                Files.list(proc).use { stream ->
                    stream
                        .filter { it.fileName.toString().toLongOrNull() != null }
                        .map { entry ->
                            runCatching { Files.readAllBytes(entry.resolve("cmdline")) }
                                .getOrNull()
                                ?.toString(Charsets.UTF_8)
                                ?.split('\u0000')
                                ?.filter { it.isNotEmpty() }
                                ?.let { displayServerForCmdline(it, displayToken) }
                        }
                        .filter { it != null }
                        .findFirst()
                        .orElse(null)
                }
            }
                .getOrNull()
        }

        /**
         * Normalized token for a [display] served on this host (`:N` or `unix:N`), else null.
         * Remote and TCP displays such as `host:0` or `localhost:10` must not match a local X
         * server with the same number in `/proc`.
         */
        internal fun localDisplayToken(display: String): String? {
            val host = display.trim().substringBeforeLast(':', missingDelimiterValue = "-")
            return if (host.isEmpty() || host == "unix") normalizeDisplayToken(display) else null
        }

        /**
         * Returns the X server name when [args] runs a known X server that serves [displayToken]
         * (normalized, e.g. `:99`), else null. The display argument must follow the server binary.
         * Non-display args (binary path, `-screen`, geometry) are skipped — must not abort the scan
         * when `normalizeDisplayToken` returns null.
         */
        internal fun displayServerForCmdline(args: List<String>, displayToken: String): String? {
            val serverIndex = args.indexOfFirst { it.substringAfterLast('/') in KNOWN_X_SERVERS }
            if (serverIndex < 0) return null
            val serves =
                args.drop(serverIndex + 1).any { normalizeDisplayToken(it) == displayToken }
            return if (serves) args[serverIndex].substringAfterLast('/') else null
        }

        private const val DEFAULT_WAYLAND_DISPLAY = "wayland-0"
        private const val XWAYLAND_SERVER = "Xwayland"
        private val PURE_X11_SERVERS = setOf("Xvfb", "Xvnc", "Xtigervnc", "Xephyr", "Xorg")
        private val KNOWN_X_SERVERS = PURE_X11_SERVERS + XWAYLAND_SERVER

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
        internal fun checkNotWayland(
            getenv: (String) -> String?,
            displayIsXwayland: (String) -> Boolean = ::defaultDisplayIsXwayland,
        ) {
            if (!detectWaylandSession(getenv, displayIsXwayland = displayIsXwayland)) return
            throw UnsupportedOperationException(
                "ffmpeg's x11grab silently captures black frames on Wayland sessions even with " +
                    "XWayland in the loop — Wayland's security model blocks framebuffer reads " +
                    "by clients other than the compositor. Detected via an Xwayland DISPLAY, " +
                    "XDG_SESSION_TYPE / WAYLAND_DISPLAY, or a residual wayland-* socket (only " +
                    "when DISPLAY is unset). " +
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
