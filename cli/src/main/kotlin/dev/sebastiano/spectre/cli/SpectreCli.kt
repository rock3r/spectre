package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.sebastiano.spectre.cli.daemon.DaemonClient
import dev.sebastiano.spectre.cli.daemon.DaemonEndpoint
import dev.sebastiano.spectre.cli.daemon.DaemonProcessLauncher
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionSummary
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
        socketPath: Path = DaemonEndpoint.defaultSocketPath(),
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
        }
    }
}

/** Runs the Spectre CLI with its default per-user daemon endpoint. */
public fun main(arguments: Array<String>): Unit = exitProcess(SpectreCli().run(arguments.asList()))

private class RootCommand(request: (DaemonRequest) -> DaemonResponse, output: Appendable) :
    CliktCommand(name = "spectre") {
    init {
        subcommands(DaemonCommand(request, output))
    }

    override fun run(): Unit = Unit
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

private fun DaemonSessionJson(summary: DaemonSessionSummary): DaemonSessionJson =
    DaemonSessionJson(id = summary.sessionId, pid = summary.targetPid)

private fun humanSession(summary: DaemonSessionSummary): String =
    "${summary.sessionId} (pid ${summary.targetPid})"

private fun daemonRequest(socketPath: Path): (DaemonRequest) -> DaemonResponse = { request ->
    DaemonClient(socketPath).use { client ->
        client.requestOrStart(request) { DaemonProcessLauncher(socketPath).start() }
    }
}

private const val EXIT_SUCCESS: Int = 0
private const val JSON_VERSION: Int = 1
private val CLI_JSON: Json = Json { encodeDefaults = true }
