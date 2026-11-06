package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.HostPlatform
import dev.sebastiano.spectre.recording.defaultLinuxSessionEnvLookup
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The one portal grant Spectre needs on Linux Wayland, requested once while someone is at the seat.
 *
 * Input, region stills, and region recording all go through one RemoteDesktop portal session
 * (keyboard, pointer, and a monitor screen cast). [request] opens it with a persistent grant: the
 * desktop shows the Share / Allow remote interaction dialog once, Spectre stores the restore token
 * (under `SPECTRE_WAYLAND_RESTORE_TOKEN_DIR`, else `$XDG_STATE_HOME/spectre`, else
 * `~/.local/state/spectre`), and the long-lived session helper keeps running, so later test JVMs
 * reuse it without a dialog. If the helper stops, the next run restores the session from the stored
 * token, again without a dialog.
 *
 * Window stills, visual-idle samples, and window recording need no portal grant on GNOME: they use
 * Mutter window streams through the same session helper.
 *
 * [preflight] never opens a dialog. [request] may, and must only run with a person at the seat.
 */
public object WaylandPortalAccess {
    /** How long [request] waits for each step of the dialog by default. */
    public const val DEFAULT_REQUEST_TIMEOUT_SECONDS: Long = 300

    private val defaultGrants: WaylandPortalGrants by lazy {
        WaylandPortalGrants(
            isApplicable = ::isApplicable,
            envLookup = defaultLinuxSessionEnvLookup,
            sessionSocket = { DefaultWaylandSessionClient.instance.sessionSocketPath() },
            socketIsLive = ::probeUnixSocket,
            openSession = { DefaultWaylandSessionClient.instance.openSessionForRequest(it) },
        )
    }

    /** True on a Linux desktop where Spectre captures and types through the Wayland portal. */
    @JvmStatic
    public fun isApplicable(): Boolean = HostPlatform.isLinux() && HostPlatform.isWayland()

    /** Reports whether the grant is present, without opening a dialog. */
    @JvmStatic public fun preflight(): WaylandPortalAccessResult = defaultGrants.preflight()

    /**
     * Opens the Share dialog when no grant is present, and keeps the session running. Each portal
     * step waits up to [timeoutSeconds] for the person at the seat. Returns at once when a session
     * is already running.
     */
    @JvmStatic
    @JvmOverloads
    public fun request(
        timeoutSeconds: Long = DEFAULT_REQUEST_TIMEOUT_SECONDS
    ): WaylandPortalAccessResult = defaultGrants.request(timeoutSeconds)
}

/** Outcome of [WaylandPortalAccess.preflight] or [WaylandPortalAccess.request]. */
@Serializable
public enum class WaylandPortalAccessStatus {
    @SerialName("granted") GRANTED,
    @SerialName("missing") MISSING,
    @SerialName("not_applicable") NOT_APPLICABLE,
}

/** Which parts of the Wayland grant are present, and what to do when one is missing. */
@Serializable
public data class WaylandPortalAccessResult(
    public val status: WaylandPortalAccessStatus,
    /** True when a Spectre session helper serves its socket now. */
    @SerialName("session_active") public val sessionActive: Boolean = false,
    @SerialName("session_socket") public val sessionSocket: String = "",
    /** True when the RemoteDesktop restore token is stored. */
    @SerialName("token_stored") public val tokenStored: Boolean = false,
    @SerialName("token_path") public val tokenPath: String = "",
    public val guidance: String = "",
) {
    public val granted: Boolean
        get() = status == WaylandPortalAccessStatus.GRANTED

    /** Multi-line text suitable for CLI relay to a person. */
    public fun relayMessage(): String = buildString {
        appendLine(
            when (status) {
                WaylandPortalAccessStatus.GRANTED -> "Wayland portal grant: granted"
                WaylandPortalAccessStatus.MISSING -> "Wayland portal grant: MISSING"
                WaylandPortalAccessStatus.NOT_APPLICABLE ->
                    "Wayland portal grant: not applicable (not needed on this platform)"
            }
        )
        if (status != WaylandPortalAccessStatus.NOT_APPLICABLE) {
            appendLine("Session helper: ${if (sessionActive) "running" else "not running"}")
            if (sessionSocket.isNotBlank()) appendLine("Session socket: $sessionSocket")
            appendLine("Restore token: ${if (tokenStored) "stored" else "not stored"}")
            if (tokenPath.isNotBlank()) appendLine("Restore token path: $tokenPath")
        }
        if (guidance.isNotBlank()) appendLine(guidance)
    }
        .trimEnd()
}

/** Testable core of [WaylandPortalAccess]. */
internal class WaylandPortalGrants(
    private val isApplicable: () -> Boolean,
    private val envLookup: (String) -> String?,
    private val sessionSocket: () -> Path?,
    private val socketIsLive: (Path) -> Boolean,
    private val openSession: (timeoutSeconds: Long) -> Unit,
) {
    fun preflight(): WaylandPortalAccessResult {
        if (!isApplicable()) return notApplicable()
        val socket = sessionSocket()
        val active = socket != null && Files.exists(socket) && socketIsLive(socket)
        val token = waylandRemoteDesktopTokenPath(envLookup)
        val stored = token != null && hasStoredRestoreToken(token)
        val status =
            if (active || stored) {
                WaylandPortalAccessStatus.GRANTED
            } else {
                WaylandPortalAccessStatus.MISSING
            }
        return WaylandPortalAccessResult(
            status = status,
            sessionActive = active,
            sessionSocket = socket?.toString().orEmpty(),
            tokenStored = stored,
            tokenPath = token?.toString().orEmpty(),
            guidance =
                when {
                    active -> SESSION_ACTIVE_GUIDANCE
                    stored -> TOKEN_ONLY_GUIDANCE
                    else -> MISSING_GUIDANCE
                },
        )
    }

    fun request(timeoutSeconds: Long): WaylandPortalAccessResult {
        require(timeoutSeconds > 0) { "timeoutSeconds must be positive, was $timeoutSeconds" }
        if (!isApplicable()) return notApplicable()
        val before = preflight()
        if (before.sessionActive) return before
        try {
            openSession(timeoutSeconds)
        } catch (error: WaylandConsentNotGrantedException) {
            return preflight()
                .copy(
                    status = WaylandPortalAccessStatus.MISSING,
                    guidance =
                        "The Share / Allow remote interaction dialog was not answered within " +
                            "${timeoutSeconds}s or was denied (${error.message}). " +
                            MISSING_GUIDANCE,
                )
        }
        return preflight()
    }

    private fun notApplicable(): WaylandPortalAccessResult =
        WaylandPortalAccessResult(
            status = WaylandPortalAccessStatus.NOT_APPLICABLE,
            guidance =
                "This is not a Linux Wayland session, so Spectre needs no xdg-desktop-portal grant.",
        )

    private companion object {
        const val SESSION_ACTIVE_GUIDANCE =
            "A Spectre Wayland session is running with the RemoteDesktop grant. Later runs reuse " +
                "it for input, screen stills, and recording without a dialog."
        const val TOKEN_ONLY_GUIDANCE =
            "The RemoteDesktop grant is stored. The next Spectre run starts a session from it " +
                "without a dialog, unless the desktop rejects the token (for example after the " +
                "monitor changed); then run `spectre permissions request` again."
        const val MISSING_GUIDANCE = WAYLAND_PERMISSIONS_REQUEST_HINT
    }
}

/** Whether a Spectre run may open the Share dialog by itself. */
internal enum class WaylandConsentPrompt(val envValue: String) {
    /** Open the dialog when no grant is stored (the default outside test runs). */
    ALLOW("allow"),
    /** Never open it: fail at once and point at `spectre permissions request`. */
    NEVER("never"),
}

/** System property that sets [WaylandConsentPrompt]; the JUnit integration sets it to `never`. */
internal const val WAYLAND_CONSENT_PROMPT_PROPERTY: String = "spectre.wayland.consentPrompt"

/** Environment variable that sets [WaylandConsentPrompt]; the helper reads the same variable. */
internal const val WAYLAND_CONSENT_PROMPT_ENV: String = "SPECTRE_WAYLAND_CONSENT_PROMPT"

/** Seconds the session helper waits for each portal step (default 60). */
internal const val WAYLAND_CONSENT_TIMEOUT_ENV: String = "SPECTRE_WAYLAND_CONSENT_TIMEOUT_SECS"

private val NEVER_VALUES = setOf("never", "no", "off", "false", "0")

/** The prompt policy: the system property wins over the environment variable. */
internal fun waylandConsentPrompt(
    property: (String) -> String?,
    env: (String) -> String?,
): WaylandConsentPrompt {
    val raw =
        property(WAYLAND_CONSENT_PROMPT_PROPERTY)?.takeIf { it.isNotBlank() }
            ?: env(WAYLAND_CONSENT_PROMPT_ENV)?.takeIf { it.isNotBlank() }
    return if (raw != null && raw.trim().lowercase() in NEVER_VALUES) {
        WaylandConsentPrompt.NEVER
    } else {
        WaylandConsentPrompt.ALLOW
    }
}

/** Token key of the session's RemoteDesktop grant. Mirrors `remote_desktop_token_key(Embedded)`. */
internal const val RD_SESSION_TOKEN_KEY: String = "rd-monitor-embedded"

/**
 * Where the helper stores the session's RemoteDesktop restore token. Mirrors
 * `restore_token_file_named` and `restore_token_state_dir` in the helper's `portal.rs`; keep them
 * in lockstep. Null when neither an override, `XDG_STATE_HOME`, nor `HOME` is set.
 */
internal fun waylandRemoteDesktopTokenPath(env: (String) -> String?): Path? {
    val fileName = remoteDesktopTokenFileName(RD_SESSION_TOKEN_KEY)
    env("SPECTRE_WAYLAND_RESTORE_TOKEN_PATH")
        ?.takeIf { it.isNotEmpty() }
        ?.let { override ->
            val base = Path.of(override)
            val name = base.fileName?.toString() ?: RD_TOKEN_FILE_PREFIX
            if (name == fileName) return base
            return (base.parent ?: Path.of(".")).resolve("$name-$RD_SESSION_TOKEN_KEY")
        }
    val dir =
        env("SPECTRE_WAYLAND_RESTORE_TOKEN_DIR")?.takeIf { it.isNotEmpty() }?.let { Path.of(it) }
            ?: env("XDG_STATE_HOME")?.takeIf { it.isNotEmpty() }?.let { Path.of(it, "spectre") }
            ?: env("HOME")
                ?.takeIf { it.isNotEmpty() }
                ?.let { Path.of(it, ".local", "state", "spectre") }
            ?: return null
    return dir.resolve(fileName)
}

/** True when [token] is a non-blank file, as the helper's `load_restore_token_named` requires. */
internal fun hasStoredRestoreToken(token: Path): Boolean =
    try {
        Files.isRegularFile(token) && Files.readString(token).isNotBlank()
    } catch (_: IOException) {
        false
    }
