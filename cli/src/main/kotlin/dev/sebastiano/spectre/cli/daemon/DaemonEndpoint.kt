package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat

/** Canonical per-user endpoint shared by daemon clients and launchers. */
public object DaemonEndpoint {
    /** Returns the daemon socket path below the supplied user home directory. */
    public fun defaultSocketPath(
        osName: String = System.getProperty("os.name").orEmpty(),
        tempDirectory: String = System.getProperty("java.io.tmpdir").orEmpty(),
        userName: String = System.getProperty("user.name").orEmpty(),
    ): Path =
        Path.of(
                baseDirectory(osName, tempDirectory),
                "sp-d-${shortUserId(userName)}",
                "daemon-v${DaemonProtocol.CurrentVersion.major}.sock",
            )
            .also(::requireSocketPathFits)

    /** Returns prior minor-version endpoints to probe while migrating to the stable major path. */
    internal fun legacySocketPaths(
        osName: String = System.getProperty("os.name").orEmpty(),
        tempDirectory: String = System.getProperty("java.io.tmpdir").orEmpty(),
        userName: String = System.getProperty("user.name").orEmpty(),
    ): List<Path> =
        (1 until DaemonProtocol.CurrentVersion.minor)
            .reversed()
            .map { minor ->
                Path.of(
                    baseDirectory(osName, tempDirectory),
                    "sp-d-${shortUserId(userName)}",
                    "daemon-v${DaemonProtocol.CurrentVersion.major}-$minor.sock",
                )
            }
            .onEach(::requireSocketPathFits)

    /** Selects the platform-specific short base directory for daemon sockets. */
    internal fun baseDirectory(osName: String, tempDirectory: String): String =
        if (osName.startsWith("Windows", ignoreCase = true)) tempDirectory else "/tmp"

    private fun shortUserId(userName: String): String =
        HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(userName.toByteArray()))
            .take(SHORT_USER_ID_LENGTH)

    private fun requireSocketPathFits(socketPath: Path) {
        require(socketPath.toString().toByteArray().size <= MAX_SOCKET_PATH_BYTES) {
            "Daemon socket path exceeds $MAX_SOCKET_PATH_BYTES bytes: $socketPath"
        }
    }

    private const val SHORT_USER_ID_LENGTH: Int = 8
    private const val MAX_SOCKET_PATH_BYTES: Int = 100
}
