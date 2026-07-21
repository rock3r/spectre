package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sebastiano.spectre.recording.screencapturekit.MacOsScreenCaptureAccess
import dev.sebastiano.spectre.recording.screencapturekit.ScreenCaptureAccessResult
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Human-facing TCC helpers (#187). Check never prompts; request may trigger the system flow and
 * must only be run with a human present. Does not go through the daemon.
 */
internal class PermissionsCommand(output: Appendable) : CliktCommand(name = "permissions") {
    init {
        subcommands(PermissionsCheckCommand(output), PermissionsRequestCommand(output))
    }

    override fun run(): Unit = Unit
}

private class PermissionsCheckCommand(private val output: Appendable) :
    CliktCommand(name = "check") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val result =
            try {
                MacOsScreenCaptureAccess.preflight()
            } catch (exception: IOException) {
                throw CliOutputException(
                    IOException(exception.message ?: "permissions check failed", exception)
                )
            } catch (exception: IllegalStateException) {
                throw CliOutputException(
                    IOException(exception.message ?: "permissions check failed", exception)
                )
            }
        printPermissions(result, json, output)
        if (!result.granted) throw ProgramResult(1)
    }
}

private class PermissionsRequestCommand(private val output: Appendable) :
    CliktCommand(name = "request") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val result =
            try {
                MacOsScreenCaptureAccess.request()
            } catch (exception: IOException) {
                throw CliOutputException(
                    IOException(exception.message ?: "permissions request failed", exception)
                )
            } catch (exception: IllegalStateException) {
                throw CliOutputException(
                    IOException(exception.message ?: "permissions request failed", exception)
                )
            }
        printPermissions(result, json, output)
        if (!result.granted) throw ProgramResult(1)
    }
}

private fun printPermissions(result: ScreenCaptureAccessResult, json: Boolean, output: Appendable) {
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

@Serializable
private data class PermissionsJson(
    val granted: Boolean,
    val binary: String,
    val settingsPath: String,
    val deepLink: String,
    val guidance: String,
)

private val PERMISSIONS_JSON = Json { encodeDefaults = true }
