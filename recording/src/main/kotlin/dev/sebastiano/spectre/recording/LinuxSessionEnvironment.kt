package dev.sebastiano.spectre.recording

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

// Per-user runtime-dir fallback for environments that scrub XDG_RUNTIME_DIR, such as Bazel test
// sandboxes (#635). Mirrored in core's LinuxDisplayProbes.kt (recording cannot depend on core) —
// keep both in lockstep.

internal const val XDG_RUNTIME_DIR_ENV: String = "XDG_RUNTIME_DIR"
internal const val DBUS_SESSION_BUS_ADDRESS_ENV: String = "DBUS_SESSION_BUS_ADDRESS"
private const val PER_USER_RUNTIME_ROOT = "/run/user"
private const val DBUS_BUS_SOCKET = "bus"

/**
 * Returns `XDG_RUNTIME_DIR` when it is set. Otherwise returns `/run/user/<uid>` when that directory
 * exists and is owned by the current user, as systemd-logind creates it. Null when neither applies.
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

/**
 * Wraps [getenv] so `XDG_RUNTIME_DIR` falls back to [resolveXdgRuntimeDir] and
 * `DBUS_SESSION_BUS_ADDRESS` falls back to `unix:path=<runtime dir>/bus` when that socket exists.
 * Every other key passes through unchanged.
 */
internal fun linuxSessionEnvLookup(
    getenv: (String) -> String?,
    currentUid: () -> Long? = ::currentUnixUid,
    isOwnedDirectory: (Path, Long) -> Boolean = ::isDirectoryOwnedBy,
    fileExists: (Path) -> Boolean = Files::exists,
): (String) -> String? = { key ->
    when (key) {
        XDG_RUNTIME_DIR_ENV ->
            resolveXdgRuntimeDir(getenv, currentUid, isOwnedDirectory)?.toString()
        DBUS_SESSION_BUS_ADDRESS_ENV ->
            getenv(key)?.takeIf { it.isNotBlank() }
                ?: resolveXdgRuntimeDir(getenv, currentUid, isOwnedDirectory)
                    ?.resolve(DBUS_BUS_SOCKET)
                    ?.takeIf(fileExists)
                    ?.let { "unix:path=$it" }
        else -> getenv(key)
    }
}

/** The process environment, with the runtime-dir and D-Bus fallbacks of [linuxSessionEnvLookup]. */
internal val defaultLinuxSessionEnvLookup: (String) -> String? =
    linuxSessionEnvLookup(System::getenv)

/**
 * Variables the `spectre-wayland-helper` process needs to reach the portal over D-Bus and to use
 * the same session directory as this JVM. Contains only keys that [lookup] resolves.
 */
internal fun waylandHelperEnvironment(lookup: (String) -> String?): Map<String, String> =
    listOf(XDG_RUNTIME_DIR_ENV, DBUS_SESSION_BUS_ADDRESS_ENV)
        .mapNotNull { key -> lookup(key)?.takeIf { it.isNotBlank() }?.let { key to it } }
        .toMap()

/** Adds [waylandHelperEnvironment] for [lookup] (the real process environment by default). */
internal fun ProcessBuilder.withWaylandHelperEnvironment(
    lookup: (String) -> String? = defaultLinuxSessionEnvLookup
): ProcessBuilder = apply { environment().putAll(waylandHelperEnvironment(lookup)) }

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
