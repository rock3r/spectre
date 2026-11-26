@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/** Operating-system family used while deriving a conservative desktop identity. */
@ExperimentalSpectreInputCoordinationApi
public enum class DesktopPlatform {
    LINUX,
    MACOS,
    WINDOWS,
}

/** Inputs captured from the target process when deriving its desktop resource key. */
@ExperimentalSpectreInputCoordinationApi
public data class DesktopIdentityEnvironment(
    public val platform: DesktopPlatform,
    public val effectiveUserId: String,
    public val environment: Map<String, String> = emptyMap(),
    /** Verified numeric Windows process-session ID; transport names are invalid. */
    public val windowsLogonSessionId: String? = null,
)

/** Resolves an existing filesystem path without exposing a production key override. */
@ExperimentalSpectreInputCoordinationApi
public fun interface CanonicalPathResolver {
    public fun canonicalize(path: Path): Path
}

/** Derives a deliberately conservative per-user/per-desktop coordination key. */
@ExperimentalSpectreInputCoordinationApi
public class DesktopIdentityResolver(
    private val canonicalPathResolver: CanonicalPathResolver = CanonicalPathResolver { path ->
        path.toRealPath()
    }
) {
    private var linuxDisplayProbe: LinuxDisplayProbe = LinuxDisplayProbe(::probeLinuxDisplay)

    /** Internal evidence seam; the public and synthetic-default constructor ABI stays unchanged. */
    internal constructor(
        canonicalPathResolver: CanonicalPathResolver,
        linuxDisplayProbe: LinuxDisplayProbe,
    ) : this(canonicalPathResolver) {
        this.linuxDisplayProbe = linuxDisplayProbe
    }

    /** Resolves the desktop identity observed inside the process that will dispatch input. */
    public fun resolve(environment: DesktopIdentityEnvironment): DesktopResourceKey {
        require(environment.effectiveUserId.isNotBlank()) { "Effective user ID must not be blank" }
        val prefix = "user:${environment.effectiveUserId}/"
        val desktopIdentity =
            when (environment.platform) {
                DesktopPlatform.MACOS -> "macos-console"
                DesktopPlatform.WINDOWS -> windowsIdentity(environment.windowsLogonSessionId)
                DesktopPlatform.LINUX -> linuxIdentity(environment.environment)
            }
        return DesktopResourceKey(prefix + desktopIdentity)
    }

    private fun windowsIdentity(logonSessionId: String?): String {
        val verified = logonSessionId?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
        return verified?.let { "windows-session:$it" } ?: "windows-user"
    }

    private fun linuxIdentity(environment: Map<String, String>): String {
        environment["WAYLAND_DISPLAY"]?.takeIf(String::isNotBlank)?.let { waylandDisplay ->
            val rawPath = Path.of(waylandDisplay)
            val socketPath =
                if (rawPath.isAbsolute) rawPath
                else {
                    val runtimeDirectory =
                        environment["XDG_RUNTIME_DIR"]?.takeIf(String::isNotBlank)
                            ?: error(
                                "Cannot prove Wayland desktop identity: relative " +
                                    "WAYLAND_DISPLAY requires XDG_RUNTIME_DIR"
                            )
                    Path.of(runtimeDirectory).resolve(rawPath)
                }
            return waylandIdentity(socketPath)
        }

        val display = environment["DISPLAY"]?.takeIf(String::isNotBlank) ?: return "linux-user"
        val localMatch = LOCAL_X11_DISPLAY.matchEntire(display)
        if (localMatch != null) {
            return when (val evidence = linuxDisplayProbe.probe(display)) {
                is LinuxDisplayEvidence.Xwayland -> waylandIdentity(evidence.compositorSocket)
                LinuxDisplayEvidence.PureX11 -> "x11-local:${localMatch.groupValues[1]}"
                is LinuxDisplayEvidence.Unknown ->
                    error(
                        "Cannot prove whether local DISPLAY $display is X11 or Xwayland: " +
                            evidence.reason
                    )
            }
        }

        val remoteServer =
            (REMOTE_X11_SCREEN_SUFFIX.matchEntire(display)?.groupValues?.get(1) ?: display)
                .lowercase()
        return "x11-remote:$remoteServer"
    }

    private fun waylandIdentity(socketPath: Path): String {
        val canonical =
            try {
                canonicalPathResolver.canonicalize(socketPath)
            } catch (failure: IOException) {
                throw IllegalStateException(
                    "Cannot canonicalize Wayland compositor socket $socketPath",
                    failure,
                )
            }
        return "wayland:$canonical"
    }

    private companion object {
        val LOCAL_X11_DISPLAY: Regex =
            Regex(
                "^(?:(?:unix/?)?:|(?:localhost|127\\.0\\.0\\.1):)(\\d+)(?:\\.\\d+)?$",
                RegexOption.IGNORE_CASE,
            )
        val REMOTE_X11_SCREEN_SUFFIX: Regex = Regex("^(.*:\\d+)\\.\\d+$")
    }
}

internal fun interface LinuxDisplayProbe {
    fun probe(display: String): LinuxDisplayEvidence
}

internal sealed interface LinuxDisplayEvidence {
    data class Xwayland(val compositorSocket: Path) : LinuxDisplayEvidence

    data object PureX11 : LinuxDisplayEvidence

    data class Unknown(val reason: String) : LinuxDisplayEvidence
}

/**
 * Finds positive lineage evidence for a local display. Absence of a visible process is deliberately
 * not interpreted as X11: `/proc` may be hidden or belong to a different PID namespace.
 */
private fun probeLinuxDisplay(display: String): LinuxDisplayEvidence {
    val token =
        normalizeLocalDisplay(display)
            ?: return LinuxDisplayEvidence.Unknown("the display is not local")
    val proc = Path.of("/proc")
    if (!Files.isDirectory(proc)) {
        return LinuxDisplayEvidence.Unknown("/proc is unavailable")
    }

    val scan =
        try {
            scanDisplayServers(proc, token)
        } catch (failure: IOException) {
            return LinuxDisplayEvidence.Unknown("cannot inspect /proc: ${failure.message}")
        }

    if (scan.matches.size != 1) {
        val detail =
            when {
                scan.matches.size > 1 -> "multiple display servers are visible"
                scan.unreadable > 0 -> "${scan.unreadable} process evidence file(s) were unreadable"
                else -> "no display server process is visible"
            }
        return LinuxDisplayEvidence.Unknown(detail)
    }

    val (process, server) = scan.matches.single()
    return if (server == XWAYLAND_SERVER) xwaylandEvidence(process)
    else LinuxDisplayEvidence.PureX11
}

private data class ProcessScan(val matches: List<Pair<Path, String>>, val unreadable: Int)

@Throws(IOException::class)
private fun scanDisplayServers(proc: Path, token: String): ProcessScan {
    val matches = mutableListOf<Pair<Path, String>>()
    var unreadableCount = 0
    Files.list(proc).use { entries ->
        entries
            .filter { it.fileName.toString().toLongOrNull() != null }
            .forEach { process ->
                val evidence =
                    try {
                        readNullSeparated(process.resolve("cmdline")) to
                            process.resolve("exe").toRealPath()
                    } catch (_: IOException) {
                        unreadableCount++
                        return@forEach
                    }
                displayServerFor(evidence.first, token, evidence.second)?.let {
                    matches += process to it
                }
            }
    }
    return ProcessScan(matches, unreadableCount)
}

private fun xwaylandEvidence(process: Path): LinuxDisplayEvidence {
    val serverEnvironment =
        try {
            readNullSeparated(process.resolve("environ"))
                .mapNotNull(::parseEnvironmentEntry)
                .toMap()
        } catch (failure: IOException) {
            return LinuxDisplayEvidence.Unknown(
                "cannot read Xwayland environment: ${failure.message}"
            )
        }
    val waylandDisplay = serverEnvironment["WAYLAND_DISPLAY"]?.takeIf(String::isNotBlank)
    if (waylandDisplay == null) {
        return LinuxDisplayEvidence.Unknown("Xwayland has no WAYLAND_DISPLAY")
    }
    val raw = Path.of(waylandDisplay)
    if (raw.isAbsolute) return LinuxDisplayEvidence.Xwayland(raw)
    val runtimeDirectory = serverEnvironment["XDG_RUNTIME_DIR"]?.takeIf(String::isNotBlank)
    return if (runtimeDirectory == null) {
        LinuxDisplayEvidence.Unknown("Xwayland has no XDG_RUNTIME_DIR")
    } else {
        LinuxDisplayEvidence.Xwayland(Path.of(runtimeDirectory).resolve(raw))
    }
}

private fun parseEnvironmentEntry(value: String): Pair<String, String>? {
    val separator = value.indexOf('=')
    return if (separator > 0) value.substring(0, separator) to value.substring(separator + 1)
    else null
}

@Throws(IOException::class)
private fun readNullSeparated(path: Path): List<String> =
    Files.readAllBytes(path).toString(Charsets.UTF_8).split('\u0000').filter(String::isNotEmpty)

internal fun displayServerFor(arguments: List<String>, token: String, executable: Path): String? {
    val server = executable.fileName.toString().takeIf(KNOWN_X_SERVERS::contains) ?: return null
    return server.takeIf {
        arguments.drop(1).any { argument -> normalizeLocalDisplay(argument) == token }
    }
}

private fun normalizeLocalDisplay(display: String): String? {
    val match =
        Regex("^(?:(?:unix/?)?:|(?:localhost|127\\.0\\.0\\.1):)(\\d+)(?:\\.\\d+)?$")
            .matchEntire(display) ?: return null
    return ":${match.groupValues[1]}"
}

private const val XWAYLAND_SERVER = "Xwayland"
private val KNOWN_X_SERVERS = setOf(XWAYLAND_SERVER, "Xvfb", "Xvnc", "Xtigervnc", "Xephyr", "Xorg")
