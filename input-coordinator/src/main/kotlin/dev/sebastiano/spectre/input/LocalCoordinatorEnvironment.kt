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
        val platform = currentPlatform()
        val baseDirectory =
            if (platform == DesktopPlatform.WINDOWS) {
                System.getenv("LOCALAPPDATA")?.takeIf(String::isNotBlank)?.let(Path::of)
                    ?: Path.of(System.getProperty("java.io.tmpdir"))
            } else {
                Path.of("/tmp")
            }
        return CoordinatorEndpointResolver.resolve(baseDirectory, effectiveUserId())
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

    private fun effectiveUserId(): String = System.getProperty("user.name")
}
