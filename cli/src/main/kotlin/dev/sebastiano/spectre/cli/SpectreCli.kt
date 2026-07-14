package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sebastiano.spectre.cli.daemon.DaemonClient
import dev.sebastiano.spectre.cli.daemon.DaemonEndpoint
import dev.sebastiano.spectre.cli.daemon.DaemonJvmProcessSummary
import dev.sebastiano.spectre.cli.daemon.DaemonProcessLauncher
import dev.sebastiano.spectre.cli.daemon.DaemonProtocol
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionSummary
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Protocol-only command-line facade over the local Spectre daemon. */
public class SpectreCli(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable = System.out,
    private val errorOutput: Appendable = System.err,
) {
    /** Creates a CLI client that auto-starts the per-user daemon when needed. */
    public constructor(
        output: Appendable = System.out,
        errorOutput: Appendable = System.err,
        socketPath: Path? = null,
    ) : this(request = daemonRequest(socketPath), output = output, errorOutput = errorOutput)

    /** Parses and executes one CLI invocation, returning zero when it succeeds. */
    public fun run(arguments: List<String>): Int {
        val command = RootCommand(request, output)
        return try {
            command.parse(arguments)
            EXIT_SUCCESS
        } catch (exception: CliktError) {
            val destination = if (exception.statusCode == EXIT_SUCCESS) output else errorOutput
            destination.append(command.getFormattedHelp(exception))
            destination.appendLine()
            exception.statusCode
        } catch (exception: IOException) {
            daemonFailure(exception.message ?: "I/O failure")
        } catch (exception: IllegalArgumentException) {
            daemonFailure(exception.message ?: "invalid daemon endpoint")
        }
    }

    private fun daemonFailure(message: String): Int {
        errorOutput.append("Spectre daemon error: $message")
        errorOutput.appendLine()
        return EXIT_FAILURE
    }
}

/** Runs the Spectre CLI with its default per-user daemon endpoint. */
public fun main(arguments: Array<String>): Unit = exitProcess(SpectreCli().run(arguments.asList()))

private class RootCommand(request: (DaemonRequest) -> DaemonResponse, output: Appendable) :
    CliktCommand(name = "spectre") {
    init {
        subcommands(PsCommand(request, output), DaemonCommand(request, output))
    }

    override fun run(): Unit = Unit
}

private class PsCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "ps") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val processes =
            when (
                val response =
                    request(DaemonRequest.ListJvmProcesses(ProcessHandle.current().pid()))
            ) {
                is DaemonResponse.JvmProcesses -> response.processes
                is DaemonResponse.Error -> throw IOException(response.message)
                else -> error("Daemon returned an unexpected response to list JVM processes")
            }
        if (json) {
            output.append(
                CLI_JSON.encodeToString(PsJson(processes = processes.map(::PsProcessJson)))
            )
        } else {
            output.append(
                if (processes.isEmpty()) "No attachable JVM processes."
                else processes.joinToString("\n", transform = ::humanProcess)
            )
        }
        output.appendLine()
    }
}

private class DaemonCommand(request: (DaemonRequest) -> DaemonResponse, output: Appendable) :
    CliktCommand(name = "daemon") {
    init {
        subcommands(DaemonStatusCommand(request, output))
    }

    override fun run(): Unit = Unit
}

private class DaemonStatusCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "status") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val sessions =
            (request(DaemonRequest.ListSessions) as? DaemonResponse.Sessions)?.sessions
                ?: error("Daemon returned an unexpected response to list sessions")
        if (json) {
            output.append(
                CLI_JSON.encodeToString(
                    DaemonStatusJson(sessions = sessions.map(::DaemonSessionJson))
                )
            )
            output.appendLine()
        } else {
            output.append(
                if (sessions.isEmpty()) "No daemon sessions."
                else sessions.joinToString("\n", transform = ::humanSession)
            )
            output.appendLine()
        }
    }
}

@Serializable
private data class DaemonStatusJson(
    val version: Int = JSON_VERSION,
    val sessions: List<DaemonSessionJson>,
)

@Serializable private data class DaemonSessionJson(val id: String, val pid: Long)

@Serializable
private data class PsJson(val version: Int = JSON_VERSION, val processes: List<PsProcessJson>)

@Serializable private data class PsProcessJson(val pid: Long, val displayName: String)

private fun DaemonSessionJson(summary: DaemonSessionSummary): DaemonSessionJson =
    DaemonSessionJson(id = summary.sessionId, pid = summary.targetPid)

private fun humanSession(summary: DaemonSessionSummary): String =
    "${summary.sessionId} (pid ${summary.targetPid})"

private fun PsProcessJson(summary: DaemonJvmProcessSummary): PsProcessJson =
    PsProcessJson(pid = summary.pid, displayName = summary.displayName)

private fun humanProcess(summary: DaemonJvmProcessSummary): String =
    "${summary.pid} ${summary.displayName}"

private fun daemonRequest(socketPath: Path?): (DaemonRequest) -> DaemonResponse =
    daemonRequest@{ request ->
        val resolvedSocketPath = socketPath ?: DaemonEndpoint.defaultSocketPath()
        if (
            socketPath == null &&
                DaemonProtocol.minimumDaemonVersion(request).minor <
                    DaemonProtocol.CurrentVersion.minor
        ) {
            DaemonEndpoint.legacySocketPaths().firstOrNull(Files::exists)?.let { legacySocketPath ->
                try {
                    return@daemonRequest DaemonClient(legacySocketPath).use { client ->
                        client.request(request)
                    }
                } catch (_: IOException) {
                    // A stale legacy socket must not prevent startup on the stable endpoint.
                }
            }
        }
        DaemonClient(resolvedSocketPath).use { client ->
            client.requestOrStart(request) { DaemonProcessLauncher(resolvedSocketPath).start() }
        }
    }

private const val EXIT_SUCCESS: Int = 0
private const val EXIT_FAILURE: Int = 1
private const val JSON_VERSION: Int = 1
private val CLI_JSON: Json = Json { encodeDefaults = true }
