package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.restrictTo
import dev.sebastiano.spectre.recording.portal.WaylandPortalAccess
import dev.sebastiano.spectre.recording.portal.WaylandPortalAccessResult
import dev.sebastiano.spectre.recording.screencapturekit.MacOsScreenCaptureAccess
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureAccessResult
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Human-facing OS permission helpers (#187). Check never prompts; request may show the system flow
 * and must only be run with a person present. Does not go through the daemon.
 * - macOS: Screen Recording (TCC).
 * - Linux Wayland: the RemoteDesktop portal grant Spectre needs for input, screen stills, and
 *   recording ([WaylandPortalAccess]). Request keeps the session running for later runs.
 * - Other platforms: nothing is needed.
 */
internal class PermissionsCommand(
    output: Appendable,
    platform: PermissionsPlatform = PermissionsPlatform.current(),
) : CliktCommand(name = "permissions") {
    init {
        subcommands(
            PermissionsCheckCommand(output, platform),
            PermissionsRequestCommand(output, platform),
        )
    }

    override fun run(): Unit = Unit
}

/** Which permission flow applies to this host. Tests pass a fake [WaylandPermissions]. */
internal sealed interface PermissionsPlatform {
    data object MacOs : PermissionsPlatform

    class LinuxWayland(val access: WaylandPermissions) : PermissionsPlatform

    data object NotNeeded : PermissionsPlatform

    companion object {
        fun current(): PermissionsPlatform =
            when {
                System.getProperty("os.name").orEmpty().lowercase().contains("mac") -> MacOs
                WaylandPortalAccess.isApplicable() -> LinuxWayland(DefaultWaylandPermissions)
                else -> NotNeeded
            }
    }
}

internal interface WaylandPermissions {
    fun preflight(): WaylandPortalAccessResult

    fun request(timeoutSeconds: Long): WaylandPortalAccessResult
}

private object DefaultWaylandPermissions : WaylandPermissions {
    override fun preflight(): WaylandPortalAccessResult = WaylandPortalAccess.preflight()

    override fun request(timeoutSeconds: Long): WaylandPortalAccessResult =
        WaylandPortalAccess.request(timeoutSeconds)
}

private class PermissionsCheckCommand(
    private val output: Appendable,
    private val platform: PermissionsPlatform,
) : CliktCommand(name = "check") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val granted =
            when (platform) {
                is PermissionsPlatform.MacOs -> {
                    val result = macOsAccess("check") { MacOsScreenCaptureAccess.preflight() }
                    printMacOs(result, json, output)
                    result.granted
                }
                is PermissionsPlatform.LinuxWayland -> {
                    val result = waylandAccess("check") { platform.access.preflight() }
                    printWayland(result, json, output)
                    result.granted
                }
                is PermissionsPlatform.NotNeeded -> {
                    printNotNeeded(json, output)
                    true
                }
            }
        if (!granted) throw ProgramResult(1)
    }
}

private class PermissionsRequestCommand(
    private val output: Appendable,
    private val platform: PermissionsPlatform,
) : CliktCommand(name = "request") {
    private val json: Boolean by option("--json").flag(default = false)
    private val timeoutSeconds: Long by
        option("--timeout")
            .long()
            .restrictTo(min = 1)
            .default(WaylandPortalAccess.DEFAULT_REQUEST_TIMEOUT_SECONDS)
            .help(
                "Linux Wayland: seconds to wait for each step of the Share dialog " +
                    "(default ${WaylandPortalAccess.DEFAULT_REQUEST_TIMEOUT_SECONDS})"
            )

    override fun run() {
        val granted =
            when (platform) {
                is PermissionsPlatform.MacOs -> {
                    val result = macOsAccess("request") { MacOsScreenCaptureAccess.request() }
                    printMacOs(result, json, output)
                    result.granted
                }
                is PermissionsPlatform.LinuxWayland -> {
                    if (!json) {
                        output.appendLine(
                            "Opening the Share / Allow remote interaction dialog on the seat if " +
                                "no grant is stored. Select the whole screen, tick Remember, and " +
                                "click Share. Waiting up to ${timeoutSeconds}s for each step."
                        )
                    }
                    val result =
                        waylandAccess("request") { platform.access.request(timeoutSeconds) }
                    printWayland(result, json, output)
                    result.granted
                }
                is PermissionsPlatform.NotNeeded -> {
                    printNotNeeded(json, output)
                    true
                }
            }
        if (!granted) throw ProgramResult(1)
    }
}

private fun macOsAccess(
    mode: String,
    block: () -> ScreenCaptureAccessResult,
): ScreenCaptureAccessResult =
    try {
        block()
    } catch (exception: IOException) {
        throw CliOutputException(
            IOException(exception.message ?: "permissions $mode failed", exception)
        )
    } catch (exception: IllegalStateException) {
        throw CliOutputException(
            IOException(exception.message ?: "permissions $mode failed", exception)
        )
    }

private fun waylandAccess(
    mode: String,
    block: () -> WaylandPortalAccessResult,
): WaylandPortalAccessResult =
    try {
        block()
    } catch (exception: IOException) {
        throw CliOutputException(
            IOException(exception.message ?: "permissions $mode failed", exception)
        )
    } catch (exception: IllegalStateException) {
        throw CliOutputException(
            IOException(exception.message ?: "permissions $mode failed", exception)
        )
    }

private fun printMacOs(result: ScreenCaptureAccessResult, json: Boolean, output: Appendable) {
    if (json) {
        output.append(
            PERMISSIONS_JSON.encodeToString(
                PermissionsJson(
                    granted = result.granted,
                    binary = result.binaryPath,
                    settingsPath = result.settingsPath,
                    deepLink = result.deepLink,
                    guidance = result.guidance,
                )
            )
        )
    } else {
        output.append(result.relayMessage())
    }
    output.appendLine()
}

private fun printWayland(result: WaylandPortalAccessResult, json: Boolean, output: Appendable) {
    if (json) {
        output.append(
            PERMISSIONS_JSON.encodeToString(
                WaylandPermissionsJson(
                    granted = result.granted,
                    status = result.status.name.lowercase(),
                    sessionActive = result.sessionActive,
                    sessionSocket = result.sessionSocket,
                    tokenStored = result.tokenStored,
                    tokenPath = result.tokenPath,
                    guidance = result.guidance,
                )
            )
        )
    } else {
        output.append(result.relayMessage())
    }
    output.appendLine()
}

private fun printNotNeeded(json: Boolean, output: Appendable) {
    if (json) {
        output.append(
            PERMISSIONS_JSON.encodeToString(
                PermissionsJson(
                    granted = true,
                    binary = "",
                    settingsPath = "",
                    deepLink = "",
                    guidance = NOT_NEEDED_MESSAGE,
                    platform = "other",
                )
            )
        )
    } else {
        output.append(NOT_NEEDED_MESSAGE)
    }
    output.appendLine()
}

private const val NOT_NEEDED_MESSAGE =
    "OS capture permissions: not needed on this platform (not applicable). macOS needs Screen " +
        "Recording, and Linux Wayland needs one RemoteDesktop portal grant."

@Serializable
private data class PermissionsJson(
    val granted: Boolean,
    val binary: String,
    val settingsPath: String,
    val deepLink: String,
    val guidance: String,
    val platform: String = "macos",
)

@Serializable
private data class WaylandPermissionsJson(
    val granted: Boolean,
    val status: String,
    val sessionActive: Boolean,
    val sessionSocket: String,
    val tokenStored: Boolean,
    val tokenPath: String,
    val guidance: String,
    val platform: String = "linux-wayland",
)

private val PERMISSIONS_JSON = Json { encodeDefaults = true }
