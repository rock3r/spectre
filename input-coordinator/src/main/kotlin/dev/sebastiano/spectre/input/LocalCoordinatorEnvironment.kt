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
        return CoordinatorEndpointResolver.resolve(baseDirectory, effectiveUserId(platform))
    }

    /** Derives the desktop key from the environment inside the process that will dispatch input. */
    public fun defaultDesktopResourceKey(): DesktopResourceKey {
        val platform = currentPlatform()
        return DesktopIdentityResolver()
            .resolve(
                DesktopIdentityEnvironment(
                    platform = platform,
                    effectiveUserId = effectiveUserId(platform),
                    environment = System.getenv(),
                    windowsLogonSessionId =
                        if (platform == DesktopPlatform.WINDOWS) System.getenv("SESSIONNAME")
                        else null,
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

    private fun effectiveUserId(platform: DesktopPlatform): String {
        if (platform == DesktopPlatform.WINDOWS) return System.getProperty("user.name")
        return try {
            val unixSystem =
                Class.forName("com.sun.security.auth.module.UnixSystem")
                    .getDeclaredConstructor()
                    .newInstance()
            (unixSystem.javaClass.getMethod("getUid").invoke(unixSystem) as Number)
                .toLong()
                .toString()
        } catch (_: ReflectiveOperationException) {
            System.getProperty("user.name")
        } catch (_: SecurityException) {
            System.getProperty("user.name")
        }
    }
}
