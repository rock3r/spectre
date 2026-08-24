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
     * Windows uses `%LOCALAPPDATA%\Temp` rather than `%LOCALAPPDATA%` itself. On hosts with a
     * filesystem filter over the user profile, an AF_UNIX socket created directly under
     * `AppData\Local` or `AppData\Roaming` binds but is unreachable — `connect` fails outright —
     * and the file then survives a clean `close()` and process exit while being impossible to
     * delete, rename, or re-bind, with `Files.exists` reporting `false` for it even though a
     * directory listing still shows it. One coordinator run therefore bricked that host's attach
     * input path (#462). The `Temp` subdirectory is not affected.
     *
     * It is deliberately **not** `%TEMP%`. This directory decides coordinator identity, because
     * `LocalCoordinatorServer` keeps its election lock here, so two processes that disagreed about
     * it would each elect themselves and hand out simultaneous leases for the same desktop.
     * `TEMP`/`TMP` are routinely overridden per process (build wrappers, CI harnesses, service
     * hosts) and can point outside the user profile entirely, e.g. `C:\Windows\Temp`. That would
     * also lose the owner-only ACL the endpoint inherits, since Windows uses
     * `BasicOwnerOnlyEndpointProtection`, which never tightens a directory's permissions itself.
     * `LOCALAPPDATA` is a Known Folder fixed at logon and already owner-only, so it supplies both a
     * stable identity and the right trust boundary.
     */
    internal fun baseDirectory(
        platform: DesktopPlatform,
        environment: Map<String, String>,
        javaTemporaryDirectory: String,
    ): Path {
        if (platform != DesktopPlatform.WINDOWS) return Path.of(POSIX_BASE_DIRECTORY)
        val localAppData = environment[WINDOWS_LOCAL_APP_DATA]?.takeIf(String::isNotBlank)
        if (localAppData != null) {
            return Path.of(localAppData, WINDOWS_TEMP_DIRECTORY_NAME)
        }
        // Only reachable when LOCALAPPDATA is unset, which is not a normal interactive session.
        // This matches what the code did before this endpoint moved at all.
        require(javaTemporaryDirectory.isNotBlank()) {
            "Could not resolve a Windows temporary directory for the input coordinator"
        }
        return Path.of(javaTemporaryDirectory)
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
}
