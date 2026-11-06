package dev.sebastiano.spectre.core

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

// Linux display-server probes behind isWaylandSession. Mirrored in the recording module's
// FfmpegBackend companion (recording cannot depend on core) — keep both in lockstep.

/**
 * Returns `XDG_RUNTIME_DIR` when it is set. Otherwise returns `/run/user/<uid>` when that directory
 * exists and is owned by the current user, as systemd-logind creates it. Null when neither applies.
 * Bazel test sandboxes scrub `XDG_RUNTIME_DIR` (#635). Mirrored in the recording module's
 * `LinuxSessionEnvironment.kt` — keep both in lockstep.
 */
internal fun resolveXdgRuntimeDir(
    getenv: (String) -> String?,
    currentUid: () -> Long? = ::currentUnixUid,
    isOwnedDirectory: (Path, Long) -> Boolean = ::isDirectoryOwnedBy,
): Path? {
    getenv(XDG_RUNTIME_DIR_ENV)
        ?.takeIf { it.isNotBlank() }
        ?.let {
            return runCatching { Path.of(it) }.getOrNull()
        }
    val uid = currentUid() ?: return null
    val candidate = Path.of(PER_USER_RUNTIME_ROOT, uid.toString())
    return candidate.takeIf { isOwnedDirectory(it, uid) }
}

/** Wraps [getenv] so `XDG_RUNTIME_DIR` falls back to [resolveXdgRuntimeDir]. */
internal fun xdgRuntimeDirAwareGetenv(
    getenv: (String) -> String?,
    currentUid: () -> Long? = ::currentUnixUid,
    isOwnedDirectory: (Path, Long) -> Boolean = ::isDirectoryOwnedBy,
): (String) -> String? = { key ->
    if (key == XDG_RUNTIME_DIR_ENV) {
        resolveXdgRuntimeDir(getenv, currentUid, isOwnedDirectory)?.toString()
    } else {
        getenv(key)
    }
}

/** The process environment, with the runtime-dir fallback of [xdgRuntimeDirAwareGetenv]. */
internal val defaultLinuxGetenv: (String) -> String? = xdgRuntimeDirAwareGetenv(System::getenv)

/** Effective uid of this JVM on Linux (owner of `/proc/self`), or null where unavailable. */
internal fun currentUnixUid(): Long? = runCatching {
    (Files.getAttribute(Path.of("/proc/self"), "unix:uid") as Int).toUInt().toLong()
}
    .getOrNull()

/** True when [path] is a real directory (not a symlink) owned by [uid]. */
internal fun isDirectoryOwnedBy(path: Path, uid: Long): Boolean = runCatching {
    Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) &&
        (Files.getAttribute(path, "unix:uid", LinkOption.NOFOLLOW_LINKS) as Int)
            .toUInt()
            .toLong() == uid
}
    .getOrDefault(false)

/**
 * Resolves the compositor socket the way libwayland does: an absolute [waylandDisplay] is used as
 * is, a relative one (default `wayland-0`) lives under [runtimeDir]. Null when it cannot resolve.
 */
internal fun resolveWaylandSocket(waylandDisplay: String?, runtimeDir: String?): Path? {
    val raw = waylandDisplay ?: DEFAULT_WAYLAND_DISPLAY
    val name = runCatching { Path.of(raw) }.getOrNull()
    if (name == null || name.isAbsolute) return name
    val dir = runtimeDir?.takeIf { it.isNotBlank() } ?: return null
    return runCatching { Path.of(dir).resolve(name) }.getOrNull()
}

/** True when [path] exists and is a socket (or other special file), not a file or directory. */
internal fun isWaylandSocket(path: Path): Boolean {
    val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }
    return attributes.getOrNull()?.isOther == true
}

/** True when [display] is served by an Xwayland process visible in `/proc`. */
internal fun defaultDisplayIsXwayland(display: String): Boolean =
    isXwaylandServer(linuxDisplayServerName(display))

/**
 * True when [display] is served by a pure X11 server: a visible Xvfb / Xvnc / Xtigervnc / Xephyr /
 * Xorg process, or `xdpyinfo` without the `XWAYLAND` extension (see recording twin).
 */
internal fun defaultDisplayIsPureX11(display: String): Boolean {
    if (isPureX11Server(linuxDisplayServerName(display))) return true
    return runCatching { xdpyinfoReportsPureX11(display) }.getOrDefault(false)
}

internal fun isXwaylandServer(serverName: String?): Boolean = serverName == XWAYLAND_SERVER

internal fun isPureX11Server(serverName: String?): Boolean = serverName in PURE_X11_SERVERS

/**
 * Scans Linux /proc pid cmdlines for the local X server serving [display]; null if none is seen.
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
 * Normalized token for a [display] served on this host (`:N` or `unix:N`), else null. Remote and
 * TCP displays such as `host:0` or `localhost:10` must not match a local X server with the same
 * number in `/proc`.
 */
internal fun localDisplayToken(display: String): String? {
    val host = display.trim().substringBeforeLast(':', missingDelimiterValue = "-")
    return if (host.isEmpty() || host == "unix") normalizeDisplayToken(display) else null
}

/**
 * Returns the X server name when [args] runs a known X server that serves [displayToken], else
 * null. The display argument must follow the server binary. Non-display args are skipped — do not
 * abort when [normalizeDisplayToken] returns null for the binary path or flags.
 */
internal fun displayServerForCmdline(args: List<String>, displayToken: String): String? {
    val serverIndex = args.indexOfFirst { it.substringAfterLast('/') in KNOWN_X_SERVERS }
    if (serverIndex < 0) return null
    val serves = args.drop(serverIndex + 1).any { normalizeDisplayToken(it) == displayToken }
    return if (serves) args[serverIndex].substringAfterLast('/') else null
}

private const val DEFAULT_WAYLAND_DISPLAY = "wayland-0"
private const val XDG_RUNTIME_DIR_ENV = "XDG_RUNTIME_DIR"
private const val PER_USER_RUNTIME_ROOT = "/run/user"
private const val XWAYLAND_SERVER = "Xwayland"
private val PURE_X11_SERVERS = setOf("Xvfb", "Xvnc", "Xtigervnc", "Xephyr", "Xorg")
private val KNOWN_X_SERVERS = PURE_X11_SERVERS + XWAYLAND_SERVER

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
        afterHost.removePrefix(":").substringBefore('.').takeIf { it.isNotEmpty() } ?: return null
    if (num.toIntOrNull() == null) return null
    return ":$num"
}

/** Bounded `xdpyinfo` probe; never blocks forever on a hung DISPLAY. */
@Suppress("TooGenericExceptionCaught")
private fun xdpyinfoReportsPureX11(display: String): Boolean {
    val process = ProcessBuilder("xdpyinfo", "-display", display).redirectErrorStream(true).start()
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
            reader.join(XDPYINFO_READER_JOIN_MS)
            return false
        }
        reader.join(XDPYINFO_READER_JOIN_MS)
        if (process.exitValue() != 0) return false
        !outputRef.get().contains("XWAYLAND", ignoreCase = true)
    } catch (_: Exception) {
        process.destroyForcibly()
        false
    }
}

private const val XDPYINFO_TIMEOUT_MS: Long = 3_000
private const val XDPYINFO_READER_JOIN_MS: Long = 500
