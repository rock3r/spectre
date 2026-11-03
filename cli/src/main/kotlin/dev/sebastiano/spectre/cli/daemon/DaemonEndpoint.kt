package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Canonical per-user endpoint shared by daemon clients and launchers.
 *
 * The client resolves the endpoint and passes it to the daemon it starts as `--socket`, so the
 * daemon never resolves it again and both always agree, including on
 * [SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE].
 */
public object DaemonEndpoint {
    /**
     * Environment variable that replaces the base directory of the daemon socket (`/tmp` on Linux
     * and macOS, `java.io.tmpdir` on Windows). It must be an absolute path. The directory goes
     * through the same ownership and permission checks as the default base.
     */
    public const val SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE: String = "SPECTRE_DAEMON_SOCKET_DIR"

    /** Returns the per-user daemon socket path below the platform or overridden base directory. */
    public fun defaultSocketPath(
        osName: String = System.getProperty("os.name").orEmpty(),
        tempDirectory: String = System.getProperty("java.io.tmpdir").orEmpty(),
        userName: String = System.getProperty("user.name").orEmpty(),
        socketDirectoryOverride: String? = System.getenv(SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE),
    ): Path =
        Path.of(
                baseDirectory(osName, tempDirectory, socketDirectoryOverride),
                "sp-d-${shortUserId(userName)}",
                "daemon-v${DaemonProtocol.CurrentVersion.major}.sock",
            )
            .also(::requireSocketPathFits)

    /** Returns prior minor-version endpoints to probe while migrating to the stable major path. */
    internal fun legacySocketPaths(
        osName: String = System.getProperty("os.name").orEmpty(),
        tempDirectory: String = System.getProperty("java.io.tmpdir").orEmpty(),
        userName: String = System.getProperty("user.name").orEmpty(),
        socketDirectoryOverride: String? = System.getenv(SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE),
    ): List<Path> =
        (1 until DaemonProtocol.CurrentVersion.minor)
            .reversed()
            .map { minor ->
                Path.of(
                    baseDirectory(osName, tempDirectory, socketDirectoryOverride),
                    "sp-d-${shortUserId(userName)}",
                    "daemon-v${DaemonProtocol.CurrentVersion.major}-$minor.sock",
                )
            }
            // Legacy names are a few bytes longer than the current one. They are optional
            // migration probes, so one that can't fit is skipped rather than failing a current
            // endpoint that does.
            .filter { path -> path.toString().toByteArray().size <= MAX_SOCKET_PATH_BYTES }

    /**
     * Selects the base directory for daemon sockets: [socketDirectoryOverride] when set, otherwise
     * the platform's short default.
     */
    internal fun baseDirectory(
        osName: String,
        tempDirectory: String,
        socketDirectoryOverride: String? = null,
    ): String {
        val override = socketDirectoryOverride?.trim().orEmpty()
        if (override.isNotEmpty()) {
            require(Path.of(override).isAbsolute) {
                "$SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE must be an absolute path, got '$override'"
            }
            return override
        }
        return if (osName.startsWith("Windows", ignoreCase = true)) tempDirectory else "/tmp"
    }

    private fun shortUserId(userName: String): String =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(userName.toByteArray()))
            .take(SHORT_USER_ID_LENGTH)

    private fun requireSocketPathFits(socketPath: Path) {
        val length = socketPath.toString().toByteArray().size
        require(length <= MAX_SOCKET_PATH_BYTES) {
            "Daemon socket path $socketPath is $length bytes, over the $MAX_SOCKET_PATH_BYTES-byte " +
                "limit for Unix domain sockets. Set $SOCKET_DIRECTORY_ENVIRONMENT_VARIABLE to a " +
                "shorter absolute directory."
        }
    }

    private const val SHORT_USER_ID_LENGTH: Int = 8
    private const val MAX_SOCKET_PATH_BYTES: Int = 100
}
