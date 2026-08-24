@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.file.Path

/**
 * Production-only discovery of the current process's canonical coordinator endpoint and desktop.
 */
@ExperimentalSpectreInputCoordinationApi
public object LocalCoordinatorEnvironment {
    /**
     * Resolves the stable same-user coordinator endpoint without consulting the working directory.
     */
    public fun defaultEndpoint(): CoordinatorEndpoint {
        val baseDirectory =
            baseDirectory(
                platform = currentPlatform(),
                environment = System.getenv(),
                javaTemporaryDirectory = System.getProperty("java.io.tmpdir").orEmpty(),
            )
        return CoordinatorEndpointResolver.resolve(baseDirectory, effectiveUserId())
    }

    /**
     * Directory that will hold the coordinator's socket, lock, and recovery ledger.
     *
     * Windows deliberately uses the per-user temporary directory rather than `%LOCALAPPDATA%`
     * itself. On hosts with a filesystem filter over the user profile, an AF_UNIX socket file
     * created directly under `AppData\Local` or `AppData\Roaming` survives a clean `close()` *and*
     * process exit, and is then permanently unusable: it cannot be deleted, renamed, or re-bound,
     * and `Files.exists` reports `false` for it while a directory listing still shows it. The first
     * coordinator shutdown therefore bricked that host's attach input path (#462). `%TEMP%` —
     * normally `AppData\Local\Temp` — is not affected, is still per-user, and matches what every
     * other platform already does.
     */
    internal fun baseDirectory(
        platform: DesktopPlatform,
        environment: Map<String, String>,
        javaTemporaryDirectory: String,
    ): Path {
        if (platform != DesktopPlatform.WINDOWS) return Path.of(POSIX_BASE_DIRECTORY)
        // Environment first, `java.io.tmpdir` last: every process on one desktop must derive the
        // same directory or they would coordinate against different coordinators, and
        // `java.io.tmpdir` can be overridden per JVM with -D (Gradle test workers do exactly that).
        val fromEnvironment = WINDOWS_TEMP_VARIABLES.firstNotNullOfOrNull { name ->
            environment[name]?.takeIf(String::isNotBlank)
        }
        val localAppDataTemp =
            environment[WINDOWS_LOCAL_APP_DATA]?.takeIf(String::isNotBlank)?.let {
                "$it${java.io.File.separator}$WINDOWS_TEMP_DIRECTORY_NAME"
            }
        val resolved =
            fromEnvironment ?: localAppDataTemp ?: javaTemporaryDirectory.takeIf(String::isNotBlank)
        require(!resolved.isNullOrBlank()) {
            "Could not resolve a Windows temporary directory for the input coordinator"
        }
        return Path.of(resolved)
    }

    /** Derives the desktop key from the environment inside the process that will dispatch input. */
    public fun defaultDesktopResourceKey(): DesktopResourceKey {
        val platform = currentPlatform()
        return DesktopIdentityResolver()
            .resolve(
                DesktopIdentityEnvironment(
                    platform = platform,
                    effectiveUserId = effectiveUserId(),
                    environment = System.getenv(),
                    // SESSIONNAME is a mutable transport label, not the numeric process session
                    // ID. Until a verified native ID is available, serialize Windows per user.
                    windowsLogonSessionId = null,
                )
            )
    }

    private fun currentPlatform(): DesktopPlatform {
        val osName = System.getProperty("os.name").orEmpty()
        return when {
            osName.startsWith("Mac", ignoreCase = true) -> DesktopPlatform.MACOS
            osName.startsWith("Windows", ignoreCase = true) -> DesktopPlatform.WINDOWS
            else -> DesktopPlatform.LINUX
        }
    }

    private fun effectiveUserId(): String =
        ProcessHandle.current().info().user().orElse(null)?.takeIf(String::isNotBlank)
            ?: error("Could not determine the current process owner identity")

    private const val POSIX_BASE_DIRECTORY: String = "/tmp"
    private const val WINDOWS_LOCAL_APP_DATA: String = "LOCALAPPDATA"
    private const val WINDOWS_TEMP_DIRECTORY_NAME: String = "Temp"
    private val WINDOWS_TEMP_VARIABLES: List<String> = listOf("TEMP", "TMP")
}
