package dev.sebastiano.spectre.cli.hotreload

import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.Optional
import java.util.Properties
import kotlin.io.path.isRegularFile

/**
 * Locates Compose Hot Reload's orchestration TCP port for a target JVM without assuming HR is
 * present (#211).
 *
 * Discovery order:
 * 1. `-Dcompose.reload.orchestration.port=` (or bare `compose.reload.orchestration.port=`) in the
 *    process argument list
 * 2. `-Dcompose.reload.pidFile=` → read `orchestration.port` from that file (PidFileInfo semantics)
 * 3. Caller-supplied pid-file path override (tests / explicit attach options)
 *
 * Absent → session is simply not reload-aware.
 */
public object HotReloadPortDiscovery {
    /**
     * Discovers the orchestration port for [targetPid], using [processArguments] when provided
     * (defaults to [ProcessHandle] lookup).
     */
    public fun discover(
        targetPid: Long,
        processArguments: List<String>? = null,
        explicitPidFile: Path? = null,
    ): HotReloadPort? {
        explicitPidFile?.let { path ->
            readPortFromPidFile(path)?.let { port ->
                return HotReloadPort(port = port, source = HotReloadPortSource.PidFile(path))
            }
        }

        val args = processArguments ?: readProcessArguments(targetPid).orEmpty()
        parsePortFromJvmArgs(args)?.let {
            return it
        }

        parsePidFilePathFromJvmArgs(args)?.let { path ->
            readPortFromPidFile(path)?.let { port ->
                return HotReloadPort(port = port, source = HotReloadPortSource.PidFile(path))
            }
        }

        return null
    }

    /** Parses a pid-file body (Java Properties format) for `orchestration.port`. */
    public fun parsePortFromPidFileProperties(content: String): Int? {
        val properties = Properties()
        properties.load(StringReader(content))
        val port = properties.getProperty(HotReloadVersions.PID_FILE_PORT_KEY)?.toIntOrNull()
        return port?.takeIf { it in 1..MAX_TCP_PORT }
    }

    /** Reads [path] if it is a regular file and returns the orchestration port, if present. */
    public fun readPortFromPidFile(path: Path): Int? {
        if (!path.isRegularFile()) return null
        return runCatching { parsePortFromPidFileProperties(Files.readString(path)) }.getOrNull()
    }

    /**
     * Extracts orchestration port from JVM argument tokens. Recognizes both `-Dkey=value` and bare
     * `key=value` forms used by some launchers.
     */
    public fun parsePortFromJvmArgs(args: List<String>): HotReloadPort? =
        args.firstNotNullOfOrNull { arg ->
            val value = propertyValue(arg, HotReloadVersions.ORCHESTRATION_PORT_PROPERTY)
            val port = value?.toIntOrNull()
            if (port != null && port in 1..MAX_TCP_PORT) {
                HotReloadPort(port = port, source = HotReloadPortSource.SystemProperty)
            } else {
                null
            }
        }

    /** Extracts the pid-file path from JVM argument tokens, if present. */
    public fun parsePidFilePathFromJvmArgs(args: List<String>): Path? =
        args.firstNotNullOfOrNull { arg ->
            val value = propertyValue(arg, HotReloadVersions.PID_FILE_PROPERTY)
            if (value.isNullOrBlank()) null else Path.of(value)
        }

    private fun propertyValue(arg: String, key: String): String? {
        val dashD = "-D$key="
        if (arg.startsWith(dashD)) return arg.removePrefix(dashD)
        val bare = "$key="
        if (arg.startsWith(bare)) return arg.removePrefix(bare)
        return null
    }

    private fun readProcessArguments(targetPid: Long): List<String>? {
        val handle =
            try {
                ProcessHandle.of(targetPid).orElse(null) ?: return null
            } catch (_: UnsupportedOperationException) {
                return null
            } catch (_: SecurityException) {
                return null
            }
        val info = handle.info()
        val args: Optional<Array<String>> = info.arguments()
        if (args.isEmpty) return null
        return args.get().toList()
    }

    private const val MAX_TCP_PORT: Int = 65_535
}

/** A discovered orchestration port and how it was found. */
public data class HotReloadPort(public val port: Int, public val source: HotReloadPortSource)

/** Provenance for a [HotReloadPort] discovery result. */
public sealed interface HotReloadPortSource {
    /** From `-Dcompose.reload.orchestration.port` (or env-equivalent arg form). */
    public data object SystemProperty : HotReloadPortSource

    /** From HR's pid file `orchestration.port` field. */
    public data class PidFile(public val path: Path) : HotReloadPortSource
}
