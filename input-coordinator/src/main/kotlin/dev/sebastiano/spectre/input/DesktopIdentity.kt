@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

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
    /**
     * Verified numeric Windows process-session ID; transport names such as SESSIONNAME are invalid.
     */
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
        val waylandDisplay = environment["WAYLAND_DISPLAY"]?.takeIf(String::isNotBlank)
        if (waylandDisplay != null) {
            val rawPath = Path.of(waylandDisplay)
            val socketPath =
                if (rawPath.isAbsolute) {
                    rawPath
                } else {
                    environment["XDG_RUNTIME_DIR"]?.let(Path::of)?.resolve(rawPath) ?: rawPath
                }
            val canonical =
                runCatching { canonicalPathResolver.canonicalize(socketPath) }
                    .getOrElse { socketPath.normalize() }
            return "wayland:$canonical"
        }
        val display = environment["DISPLAY"]?.takeIf(String::isNotBlank) ?: return "linux-user"
        val localMatch = LOCAL_X11_DISPLAY.matchEntire(display)
        return if (localMatch != null) {
            "x11-local:${localMatch.groupValues[1]}"
        } else {
            val remoteServer =
                REMOTE_X11_SCREEN_SUFFIX.matchEntire(display)?.groupValues?.get(1) ?: display
            "x11-remote:$remoteServer"
        }
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
