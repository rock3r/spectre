package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import dev.sebastiano.spectre.cli.daemon.DaemonClient
import dev.sebastiano.spectre.cli.daemon.DaemonEndpoint
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonJvmProcessSummary
import dev.sebastiano.spectre.cli.daemon.DaemonProcessLauncher
import dev.sebastiano.spectre.cli.daemon.DaemonProtocol
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionSummary
import dev.sebastiano.spectre.cli.mcp.SpectreMcpServer
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
    private val shutdownRequest: () -> DaemonResponse = { request(DaemonRequest.Shutdown) },
    private val output: Appendable = System.out,
    private val errorOutput: Appendable = System.err,
) {
    /** Creates a CLI client that auto-starts the per-user daemon when needed. */
    public constructor(
        output: Appendable = System.out,
        errorOutput: Appendable = System.err,
        socketPath: Path? = null,
    ) : this(
        request = daemonRequest(socketPath),
        shutdownRequest = daemonShutdownRequest(socketPath),
        output = output,
        errorOutput = errorOutput,
    )

    /** Parses and executes one CLI invocation, returning zero when it succeeds. */
    public fun run(arguments: List<String>): Int {
        val command = RootCommand(request, shutdownRequest, output)
        return try {
            command.parse(arguments)
            EXIT_SUCCESS
        } catch (exception: CliktError) {
            val destination = if (exception.statusCode == EXIT_SUCCESS) output else errorOutput
            destination.append(command.getFormattedHelp(exception))
            destination.appendLine()
            if (exception.statusCode == EXIT_SUCCESS) EXIT_SUCCESS else EXIT_USAGE_FAILURE
        } catch (exception: CliOutputException) {
            outputFailure(exception.message ?: "failed to write command output")
        } catch (exception: DaemonCommandException) {
            daemonFailure(exception.message ?: "daemon command failed", exitCodeFor(exception.code))
        } catch (exception: IOException) {
            daemonFailure(exception.message ?: "I/O failure")
        } catch (exception: IllegalArgumentException) {
            daemonFailure(exception.message ?: "invalid daemon endpoint")
        }
    }

    private fun daemonFailure(message: String, exitCode: Int = EXIT_DAEMON_FAILURE): Int {
        errorOutput.append("Spectre daemon error: $message")
        errorOutput.appendLine()
        return exitCode
    }

    private fun outputFailure(message: String): Int {
        errorOutput.append("Spectre output error: $message")
        errorOutput.appendLine()
        return EXIT_OUTPUT_FAILURE
    }
}

/** Runs the Spectre CLI with its default per-user daemon endpoint. */
public fun main(arguments: Array<String>): Unit =
    exitProcess(
        jdkPreflightError()?.let { message ->
            System.err.println(message)
            EXIT_DAEMON_FAILURE
        } ?: run { SpectreCli().run(arguments.asList()) }
    )

internal fun jdkPreflightError(
    featureVersion: Int = Runtime.version().feature(),
    hasAttachModule: Boolean = ModuleLayer.boot().findModule("jdk.attach").isPresent,
): String? =
    when {
        featureVersion < MINIMUM_JDK_FEATURE_VERSION ->
            "Spectre requires JDK $MINIMUM_JDK_FEATURE_VERSION or later; found Java $featureVersion."
        !hasAttachModule ->
            "Spectre requires a full JDK with the jdk.attach module; the current Java runtime does not provide it."
        else -> null
    }

private const val MINIMUM_JDK_FEATURE_VERSION: Int = 21

private class RootCommand(
    request: (DaemonRequest) -> DaemonResponse,
    shutdownRequest: () -> DaemonResponse,
    output: Appendable,
) : CliktCommand(name = "spectre") {
    init {
        subcommands(
            AttachCommand(request, output),
            DetachCommand(request, output),
            WindowsCommand(request, output),
            TreeCommand(request, output),
            FindCommand(request, output),
            ClickCommand(request, output),
            TypeCommand(request, output),
            ScreenshotCommand(request, output),
            RecordCommand(request, output),
            PsCommand(request, output),
            DaemonCommand(request, shutdownRequest, output),
            McpCommand(request),
        )
    }

    override fun run(): Unit = Unit
}

private class McpCommand(private val request: (DaemonRequest) -> DaemonResponse) :
    CliktCommand(name = "mcp") {
    override fun run(): Unit = SpectreMcpServer.run(request)
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class TypeCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "type") {
    private val sessionId: String by argument()
    private val text: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (val response = request(DaemonRequest.TypeText(sessionId, text))) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to type")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Typed text.")
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class ScreenshotCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "screenshot") {
    private val sessionId: String by argument()
    private val outputPath: Path? by option("--output").path()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val pngBytes =
            when (val response = request(DaemonRequest.Screenshot(sessionId))) {
                is DaemonResponse.Screenshot -> response.pngBytes
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to screenshot")
            }
        val path =
            try {
                val destination = outputPath ?: Files.createTempFile("spectre-screenshot-", ".png")
                Files.write(destination, pngBytes)
                destination
            } catch (exception: IOException) {
                throw CliOutputException(exception)
            }
        if (json) output.append(CLI_JSON.encodeToString(ScreenshotJson(path = path.toString())))
        else output.append(path.toString())
        output.appendLine()
    }
}

private class RecordCommand(request: (DaemonRequest) -> DaemonResponse, output: Appendable) :
    CliktCommand(name = "record") {
    init {
        subcommands(RecordStartCommand(request, output), RecordStopCommand(request, output))
    }

    override fun run(): Unit = Unit
}

private class RecordStartCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "start") {
    private val sessionId: String by argument()
    private val outputPath: Path? by option("--output").path()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val destination =
            try {
                (outputPath
                        ?: Files.createTempFile("spectre-recording-", ".mp4")
                            .also(Files::deleteIfExists))
                    .toAbsolutePath()
                    .normalize()
            } catch (exception: IOException) {
                throw CliOutputException(exception)
            }
        val recording =
            when (
                val response =
                    request(DaemonRequest.StartRecording(sessionId, destination.toString()))
            ) {
                is DaemonResponse.RecordingStarted -> response
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to record start")
            }
        printRecording(recording.sessionId, recording.outputPath, json, output, "Recording to")
    }
}

private class RecordStopCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "stop") {
    private val sessionId: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val recording =
            when (val response = request(DaemonRequest.StopRecording(sessionId))) {
                is DaemonResponse.RecordingStopped -> response
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to record stop")
            }
        printRecording(
            recording.sessionId,
            recording.outputPath,
            json,
            output,
            "Recording saved to",
        )
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class ClickCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "click") {
    private val sessionId: String by argument()
    private val nodeKey: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (val response = request(DaemonRequest.Click(sessionId, nodeKey))) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to click")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Clicked $nodeKey.")
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class FindCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "find") {
    private val sessionId: String by argument()
    private val testTag: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val nodes =
            when (val response = request(DaemonRequest.FindByTestTag(sessionId, testTag))) {
                is DaemonResponse.Nodes -> response.nodes
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to find")
            }
        if (json) output.append(CLI_JSON.encodeToString(TreeJson(nodes = nodes.map(::NodeJson))))
        else
            output.append(nodes.joinToString("\n") { node -> "${node.key} ${node.role ?: "Node"}" })
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class TreeCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "tree") {
    private val sessionId: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val nodes =
            when (val response = request(DaemonRequest.AllNodes(sessionId))) {
                is DaemonResponse.Nodes -> response.nodes
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to tree")
            }
        if (json) output.append(CLI_JSON.encodeToString(TreeJson(nodes = nodes.map(::NodeJson))))
        else
            output.append(nodes.joinToString("\n") { node -> "${node.key} ${node.role ?: "Node"}" })
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class WindowsCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "windows") {
    private val sessionId: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val windows =
            when (val response = request(DaemonRequest.Windows(sessionId))) {
                is DaemonResponse.Windows -> response.windows
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to windows")
            }
        if (json) {
            output.append(CLI_JSON.encodeToString(WindowsJson(windows = windows.map(::WindowJson))))
        } else
            output.append(
                windows.joinToString("\n") { window ->
                    "${window.index} ${window.title ?: "(untitled)"}"
                }
            )
        output.appendLine()
    }
}

private class DetachCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "detach") {
    private val sessionId: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val detachedSessionId =
            when (val response = request(DaemonRequest.Detach(sessionId))) {
                is DaemonResponse.Detached -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to detach")
            }
        if (json) {
            output.append(CLI_JSON.encodeToString(DetachJson(id = detachedSessionId)))
        } else {
            output.append("Detached $detachedSessionId.")
        }
        output.appendLine()
    }
}

private class AttachCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "attach") {
    private val targetPid: Long by argument().long()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val session =
            when (val response = request(DaemonRequest.Attach(targetPid))) {
                is DaemonResponse.Attached -> response
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to attach")
            }
        if (json) {
            output.append(
                CLI_JSON.encodeToString(AttachJson(id = session.sessionId, pid = session.targetPid))
            )
        } else {
            output.append(humanSession(DaemonSessionSummary(session.sessionId, session.targetPid)))
        }
        output.appendLine()
    }
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
                is DaemonResponse.Error -> throw DaemonCommandException(response)
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

private class DaemonCommand(
    request: (DaemonRequest) -> DaemonResponse,
    shutdownRequest: () -> DaemonResponse,
    output: Appendable,
) : CliktCommand(name = "daemon") {
    init {
        subcommands(
            DaemonStatusCommand(request, output),
            DaemonKillCommand(shutdownRequest, output),
        )
    }

    override fun run(): Unit = Unit
}

private class DaemonKillCommand(
    private val shutdownRequest: () -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "kill") {
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        when (val response = shutdownRequest()) {
            DaemonResponse.ShuttingDown -> Unit
            is DaemonResponse.Error -> throw DaemonCommandException(response)
            else -> error("Daemon returned an unexpected response to shutdown")
        }
        if (json) output.append(CLI_JSON.encodeToString(DaemonKillJson()))
        else output.append("Daemon stopped.")
        output.appendLine()
    }
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

@Serializable
private data class DaemonKillJson(val version: Int = JSON_VERSION, val stopped: Boolean = true)

@Serializable private data class DaemonSessionJson(val id: String, val pid: Long)

@Serializable
private data class AttachJson(val version: Int = JSON_VERSION, val id: String, val pid: Long)

@Serializable private data class DetachJson(val version: Int = JSON_VERSION, val id: String)

@Serializable private data class CompletionJson(val version: Int = JSON_VERSION, val id: String)

@Serializable private data class ScreenshotJson(val version: Int = JSON_VERSION, val path: String)

@Serializable
private data class RecordingJson(val version: Int = JSON_VERSION, val id: String, val path: String)

@Serializable
private data class WindowsJson(val version: Int = JSON_VERSION, val windows: List<WindowJson>)

@Serializable
private data class TreeJson(val version: Int = JSON_VERSION, val nodes: List<NodeJson>)

@Serializable
private data class NodeJson(
    val key: String,
    val testTag: String?,
    val texts: List<String>,
    val editableText: String?,
    val role: String?,
    val contentDescription: String?,
    val isFocused: Boolean,
    val isVisible: Boolean,
    val bounds: RectJson,
)

@Serializable
private data class WindowJson(
    val index: Int,
    val surfaceId: String,
    val title: String?,
    val isPopup: Boolean,
    val bounds: RectJson,
)

@Serializable private data class RectJson(val x: Int, val y: Int, val width: Int, val height: Int)

@Serializable
private data class PsJson(val version: Int = JSON_VERSION, val processes: List<PsProcessJson>)

@Serializable private data class PsProcessJson(val pid: Long, val displayName: String)

private fun DaemonSessionJson(summary: DaemonSessionSummary): DaemonSessionJson =
    DaemonSessionJson(id = summary.sessionId, pid = summary.targetPid)

private fun printRecording(
    sessionId: String,
    outputPath: String,
    json: Boolean,
    output: Appendable,
    label: String,
) {
    if (json)
        output.append(CLI_JSON.encodeToString(RecordingJson(id = sessionId, path = outputPath)))
    else output.append("$label $outputPath.")
    output.appendLine()
}

private fun humanSession(summary: DaemonSessionSummary): String =
    "${summary.sessionId} (pid ${summary.targetPid})"

private fun PsProcessJson(summary: DaemonJvmProcessSummary): PsProcessJson =
    PsProcessJson(pid = summary.pid, displayName = summary.displayName)

private fun humanProcess(summary: DaemonJvmProcessSummary): String =
    "${summary.pid} ${summary.displayName}"

@OptIn(ExperimentalSpectreAgentApi::class)
private fun WindowJson(window: WindowSummaryDto): WindowJson =
    WindowJson(
        index = window.index,
        surfaceId = window.surfaceId,
        title = window.title,
        isPopup = window.isPopup,
        bounds =
            RectJson(window.bounds.x, window.bounds.y, window.bounds.width, window.bounds.height),
    )

@OptIn(ExperimentalSpectreAgentApi::class)
private fun NodeJson(node: NodeSnapshotDto): NodeJson =
    NodeJson(
        key = node.key,
        testTag = node.testTag,
        texts = node.texts,
        editableText = node.editableText,
        role = node.role,
        contentDescription = node.contentDescription,
        isFocused = node.isFocused,
        isVisible = node.isVisible,
        bounds = RectJson(node.bounds.x, node.bounds.y, node.bounds.width, node.bounds.height),
    )

private fun daemonRequest(socketPath: Path?): (DaemonRequest) -> DaemonResponse =
    daemonRequest@{ request ->
        val resolvedSocketPath = socketPath ?: DaemonEndpoint.defaultSocketPath()
        if (
            socketPath == null &&
                DaemonProtocol.minimumDaemonVersion(request).minor <
                    DaemonProtocol.CurrentVersion.minor
        ) {
            for (legacySocketPath in DaemonEndpoint.legacySocketPaths()) {
                if (!Files.exists(legacySocketPath)) continue
                try {
                    return@daemonRequest DaemonClient(legacySocketPath).use { client ->
                        client.request(request)
                    }
                } catch (_: IOException) {
                    // A stale legacy socket must not prevent probing older endpoints or new
                    // startup.
                }
            }
        }
        DaemonClient(resolvedSocketPath).use { client ->
            client.requestOrStart(request) { DaemonProcessLauncher(resolvedSocketPath).start() }
        }
    }

private fun daemonShutdownRequest(socketPath: Path?): () -> DaemonResponse = daemonShutdownRequest@{
    val resolvedSocketPath = socketPath ?: DaemonEndpoint.defaultSocketPath()
    if (
        socketPath == null &&
            DaemonProtocol.minimumDaemonVersion(DaemonRequest.Shutdown).minor <
                DaemonProtocol.CurrentVersion.minor
    ) {
        for (legacySocketPath in DaemonEndpoint.legacySocketPaths()) {
            if (!Files.exists(legacySocketPath)) continue
            try {
                return@daemonShutdownRequest DaemonClient(legacySocketPath).use { client ->
                    client.request(DaemonRequest.Shutdown)
                }
            } catch (_: IOException) {
                // A stale legacy socket must not prevent probing another endpoint.
            }
        }
    }
    DaemonClient(resolvedSocketPath).use { client -> client.request(DaemonRequest.Shutdown) }
}

private const val EXIT_SUCCESS: Int = 0
private const val EXIT_OUTPUT_FAILURE: Int = 1
private const val EXIT_USAGE_FAILURE: Int = 2
private const val EXIT_ATTACH_FAILURE: Int = 3
private const val EXIT_TARGET_NOT_FOUND: Int = 4
private const val EXIT_DAEMON_FAILURE: Int = 5
private const val JSON_VERSION: Int = 1
private val CLI_JSON: Json = Json { encodeDefaults = true }

private class CliOutputException(cause: IOException) : IOException(cause.message, cause)

private class DaemonCommandException(response: DaemonResponse.Error) :
    IOException(response.message) {
    val code: DaemonErrorCode = response.code
}

private fun exitCodeFor(code: DaemonErrorCode): Int =
    when (code) {
        DaemonErrorCode.AttachFailed -> EXIT_ATTACH_FAILURE
        DaemonErrorCode.SessionNotFound -> EXIT_TARGET_NOT_FOUND
        DaemonErrorCode.ProtocolError,
        DaemonErrorCode.ShutdownInProgress,
        DaemonErrorCode.OperationFailed -> EXIT_DAEMON_FAILURE
    }
