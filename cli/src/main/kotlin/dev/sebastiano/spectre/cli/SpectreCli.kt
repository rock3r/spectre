package dev.sebastiano.spectre.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.github.ajalt.clikt.parameters.types.path
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.launch.LaunchAndAttach
import dev.sebastiano.spectre.agent.launch.LaunchException
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import dev.sebastiano.spectre.cli.daemon.CaptureSessionReport
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
import java.nio.file.NoSuchFileException
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
        val command = RootCommand(request, shutdownRequest, output, errorOutput)
        return try {
            command.parse(arguments)
            EXIT_SUCCESS
        } catch (exception: ProgramResult) {
            // Intentional early exit with a custom status (e.g. permissions denied → 1). Do not
            // treat as a usage error or print Clikt help — the command already wrote its output.
            exception.statusCode
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
        minimumJdkPreflightError()?.let { message ->
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

internal fun minimumJdkPreflightError(featureVersion: Int = Runtime.version().feature()): String? =
    if (featureVersion < MINIMUM_JDK_FEATURE_VERSION) {
        "Spectre requires JDK $MINIMUM_JDK_FEATURE_VERSION or later; found Java $featureVersion."
    } else {
        null
    }

private const val MINIMUM_JDK_FEATURE_VERSION: Int = 21

private class RootCommand(
    request: (DaemonRequest) -> DaemonResponse,
    shutdownRequest: () -> DaemonResponse,
    output: Appendable,
    errorOutput: Appendable,
) : CliktCommand(name = "spectre") {
    init {
        subcommands(
            AttachCommand(request, output),
            DetachCommand(request, output),
            LaunchCommand(output, errorOutput),
            WindowsCommand(request, output),
            TreeCommand(request, output),
            FindCommand(request, output),
            FindByTextCommand(request, output),
            WaitForNodeCommand(request, output),
            WaitForVisualIdleCommand(request, output),
            WaitCommand(request, output),
            ClickCommand(request, output),
            DoubleClickCommand(request, output),
            LongClickCommand(request, output),
            SwipeCommand(request, output),
            ScrollWheelCommand(request, output),
            PressKeyCommand(request, output),
            TypeCommand(request, output),
            ScreenshotCommand(request, output),
            CaptureCommand(request, output),
            CapturesCommand(request, output),
            RecordCommand(request, output),
            PermissionsCommand(output),
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
    private val windowIndex: Int? by option("--window").int()
    private val surfaceId: String? by option("--surface")
    private val fullscreen: Boolean by
        option(
                "--fullscreen",
                help = "Capture the full virtual desktop instead of a tracked window",
            )
            .flag(default = false)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        if (fullscreen && (windowIndex != null || surfaceId != null)) {
            throw CliktError(
                "screenshot: --fullscreen cannot be combined with --window or --surface"
            )
        }
        val pngBytes =
            when (
                val response =
                    request(
                        DaemonRequest.Screenshot(
                            sessionId = sessionId,
                            windowIndex = windowIndex,
                            surfaceId = surfaceId,
                            fullscreen = fullscreen,
                        )
                    )
            ) {
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

@OptIn(ExperimentalSpectreAgentApi::class)
private class CaptureCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "capture") {
    private val sessionId: String by argument()
    private val windowIndex: Int by option("--window").int().default(0)
    private val outDir: Path? by option("--out-dir").path()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val response =
            request(
                DaemonRequest.Capture(
                    sessionId = sessionId,
                    windowIndex = windowIndex,
                    outDir = outDir?.toAbsolutePath()?.normalize()?.toString(),
                )
            )
        val capture =
            when (response) {
                is DaemonResponse.Capture -> response
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to capture")
            }
        if (json) {
            output.append(
                CLI_JSON.encodeToString(
                    CaptureJson(
                        directory = capture.directory,
                        captureJson = capture.captureJsonPath,
                        screenshotPng = capture.screenshotPngPath,
                        schemaVersion = capture.schemaVersion,
                        windowIndex = capture.windowIndex,
                        nodeCount = capture.nodeCount,
                        taggedNodeCount = capture.taggedNodeCount,
                        textedNodeCount = capture.textedNodeCount,
                        imageWidth = capture.imageWidth,
                        imageHeight = capture.imageHeight,
                        captureDurationMs = capture.captureDurationMs,
                        skillHint = CaptureSessionReport.CAPTURE_SKILL_NAME,
                    )
                )
            )
        } else {
            output.append("Captured ${capture.nodeCount} nodes → ${capture.directory}")
            output.appendLine()
            output.append(
                "See agent skill `${CaptureSessionReport.CAPTURE_SKILL_NAME}` for capture.json / jq workflows."
            )
        }
        output.appendLine()
    }
}

private class RecordCommand(request: (DaemonRequest) -> DaemonResponse, output: Appendable) :
    CliktCommand(name = "record") {
    init {
        subcommands(
            RecordStartCommand(request, output),
            RecordStopCommand(request, output),
            RecordStatusCommand(request, output),
        )
    }

    override fun run(): Unit = Unit
}

private class RecordStartCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "start") {
    private val sessionId: String by argument()
    private val outputPath: Path? by option("--output").path()
    /** Prefer `--window`; `--window-index` is kept as a compatibility alias. */
    private val windowIndex: Int? by option("--window", "--window-index").int()
    private val fullscreen: Boolean by
        option(
                "--fullscreen",
                help =
                    "Record the full primary display instead of a tracked window " +
                        "(multi-monitor desktops are not supported yet)",
            )
            .flag(default = false)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        if (fullscreen && windowIndex != null) {
            throw CliktError(
                "record start: --fullscreen cannot be combined with --window / --window-index"
            )
        }
        // Null --output: daemon allocates under the capture root (ledger + live prune).
        val destination =
            try {
                outputPath?.toAbsolutePath()?.normalize()?.toString()
            } catch (exception: IOException) {
                throw CliOutputException(exception)
            }
        val recording =
            when (
                val response =
                    request(
                        DaemonRequest.StartRecording(
                            sessionId = sessionId,
                            outputPath = destination,
                            windowIndex = windowIndex ?: 0,
                            fullscreen = fullscreen,
                        )
                    )
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

private class RecordStatusCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "status") {
    private val sessionId: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val status =
            when (val response = request(DaemonRequest.RecordingStatus(sessionId))) {
                is DaemonResponse.RecordingStatus -> response
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to record status")
            }
        if (json) {
            output.append(
                CLI_JSON.encodeToString(
                    RecordingStatusJson(
                        sessionId = status.sessionId,
                        active = status.active,
                        path = status.outputPath,
                        captureDirectory = status.captureDirectory,
                    )
                )
            )
            output.appendLine()
            return
        }
        if (!status.active) {
            output.append("No active recording for session ${status.sessionId}")
            output.appendLine()
            return
        }
        output.append("Recording active: ${status.outputPath}")
        status.captureDirectory?.let { output.append(" (capture dir $it)") }
        output.appendLine()
    }
}

@Serializable
private data class RecordingStatusJson(
    val sessionId: String,
    val active: Boolean,
    val path: String? = null,
    val captureDirectory: String? = null,
)

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
private class DoubleClickCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "double-click") {
    private val sessionId: String by argument()
    private val nodeKey: String by argument()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (val response = request(DaemonRequest.DoubleClick(sessionId, nodeKey))) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to double-click")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Double-clicked $nodeKey.")
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class LongClickCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "long-click") {
    private val sessionId: String by argument()
    private val nodeKey: String by argument()
    private val holdForMs: Long by option("--hold-ms").long().default(500)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (
                val response =
                    request(DaemonRequest.LongClick(sessionId, nodeKey, holdForMs = holdForMs))
            ) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to long-click")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Long-clicked $nodeKey (${holdForMs}ms).")
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class SwipeCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "swipe") {
    private val sessionId: String by argument()
    private val fromNodeKey: String? by option("--from")
    private val toNodeKey: String? by option("--to")
    private val startX: Int? by option("--start-x").int()
    private val startY: Int? by option("--start-y").int()
    private val endX: Int? by option("--end-x").int()
    private val endY: Int? by option("--end-y").int()
    private val steps: Int by option("--steps").int().default(12)
    private val durationMs: Long by option("--duration-ms").long().default(200)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (
                val response =
                    request(
                        DaemonRequest.Swipe(
                            sessionId = sessionId,
                            fromNodeKey = fromNodeKey,
                            toNodeKey = toNodeKey,
                            startX = startX,
                            startY = startY,
                            endX = endX,
                            endY = endY,
                            steps = steps,
                            durationMs = durationMs,
                        )
                    )
            ) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to swipe")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Swipe completed.")
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class ScrollWheelCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "scroll-wheel") {
    private val sessionId: String by argument()
    private val nodeKey: String by argument()
    private val wheelClicks: Int by argument().int()
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (
                val response = request(DaemonRequest.ScrollWheel(sessionId, nodeKey, wheelClicks))
            ) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to scroll-wheel")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Scrolled $nodeKey by $wheelClicks wheel clicks.")
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class PressKeyCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "press-key") {
    private val sessionId: String by argument()
    private val keyCode: Int by argument().int()
    private val modifiers: Int by option("--modifiers").int().default(0)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (val response = request(DaemonRequest.PressKey(sessionId, keyCode, modifiers))) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to press-key")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Pressed key $keyCode (modifiers=$modifiers).")
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
private class FindByTextCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "find-text") {
    private val sessionId: String by argument()
    private val text: String by argument()
    private val substring: Boolean by option("--substring").flag(default = false)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val nodes =
            when (
                val response =
                    request(
                        DaemonRequest.FindByText(
                            sessionId = sessionId,
                            text = text,
                            exact = !substring,
                        )
                    )
            ) {
                is DaemonResponse.Nodes -> response.nodes
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to find-text")
            }
        if (json) output.append(CLI_JSON.encodeToString(TreeJson(nodes = nodes.map(::NodeJson))))
        else
            output.append(nodes.joinToString("\n") { node -> "${node.key} ${node.role ?: "Node"}" })
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class WaitForNodeCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "wait-for-node") {
    private val sessionId: String by argument()
    private val tag: String? by option("--tag")
    private val text: String? by option("--text")
    private val timeoutMs: Long by option("--timeout-ms").long().default(5_000)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val nodes =
            when (
                val response =
                    request(
                        DaemonRequest.WaitForNode(
                            sessionId = sessionId,
                            tag = tag,
                            text = text,
                            timeoutMs = timeoutMs,
                        )
                    )
            ) {
                is DaemonResponse.Nodes -> response.nodes
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to wait-for-node")
            }
        if (json) output.append(CLI_JSON.encodeToString(TreeJson(nodes = nodes.map(::NodeJson))))
        else
            output.append(
                nodes.joinToString("\n") { node ->
                    "${node.key} ${node.testTag ?: node.role ?: "Node"}"
                }
            )
        output.appendLine()
    }
}

@OptIn(ExperimentalSpectreAgentApi::class)
private class WaitForVisualIdleCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "wait-for-visual-idle") {
    private val sessionId: String by argument()
    private val timeoutMs: Long by option("--timeout-ms").long().default(5_000)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        val completedSessionId =
            when (
                val response =
                    request(
                        DaemonRequest.WaitForVisualIdle(
                            sessionId = sessionId,
                            timeoutMs = timeoutMs,
                        )
                    )
            ) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to wait-for-visual-idle")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Visual idle for session $completedSessionId.")
        output.appendLine()
    }
}

/**
 * `spectre wait --reload-settled <session>` — await Compose Hot Reload settle (#211).
 *
 * Only meaningful on reload-aware sessions; non-HR targets fail closed immediately with
 * `hotReloadUnavailable`.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
private class WaitCommand(
    private val request: (DaemonRequest) -> DaemonResponse,
    private val output: Appendable,
) : CliktCommand(name = "wait") {
    private val sessionId: String by argument(help = "Attached session id")
    private val reloadSettled: Boolean by
        option(
                "--reload-settled",
                help =
                    "Wait until a Compose Hot Reload completes (request → result → UIRendered → Ping/Ack).",
            )
            .flag(default = false)
    private val timeoutMs: Long by option("--timeout-ms").long().default(60_000)
    private val json: Boolean by option("--json").flag(default = false)

    override fun run() {
        if (!reloadSettled) {
            throw CliktError(
                "Specify --reload-settled (the only wait mode supported by this command)."
            )
        }
        val completedSessionId =
            when (
                val response =
                    request(
                        DaemonRequest.WaitForReloadSettled(
                            sessionId = sessionId,
                            timeoutMs = timeoutMs,
                        )
                    )
            ) {
                is DaemonResponse.Completed -> response.sessionId
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to wait --reload-settled")
            }
        if (json) output.append(CLI_JSON.encodeToString(CompletionJson(id = completedSessionId)))
        else output.append("Reload settled for session $completedSessionId.")
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
        val detached =
            when (val response = request(DaemonRequest.Detach(sessionId))) {
                is DaemonResponse.Detached -> response
                is DaemonResponse.Error -> throw DaemonCommandException(response)
                else -> error("Daemon returned an unexpected response to detach")
            }
        if (json) {
            output.append(
                CLI_JSON.encodeToString(
                    DetachJson(
                        id = detached.sessionId,
                        captureCount = detached.captureCount,
                        captureBytes = detached.captureBytes,
                        capturePaths = detached.capturePaths,
                        pruneCommand = detached.pruneCommand,
                        skillHint = detached.skillHint,
                    )
                )
            )
        } else {
            output.append("Detached ${detached.sessionId}.")
            if (detached.captureCount > 0) {
                output.appendLine()
                output.append(
                    "This session wrote ${detached.captureCount} captures, " +
                        formatCaptureMegabytes(detached.captureBytes) +
                        " MB:"
                )
                detached.capturePaths.forEach { path ->
                    output.appendLine()
                    output.append("  $path")
                }
                detached.pruneCommand?.let { command ->
                    output.appendLine()
                    output.append("Prune with: $command")
                }
                detached.skillHint?.let { skill ->
                    output.appendLine()
                    output.append("See agent skill `$skill` for capture.json / jq workflows.")
                }
            }
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

/**
 * Launch a command, wait through staged readiness, attach Spectre, print a summary, then tear down
 * when the command exits this process (or when `--once` finishes the first window check).
 *
 * Prefer prod-like launches. Gradle `run` / `hotRun` are supported but warn loudly.
 *
 * Examples:
 * ```
 * spectre launch -- java -jar app/build/libs/app.jar
 * spectre launch -C /path/to/repo -- ./gradlew :app:run
 * spectre launch --once -- java -cp fixture.jar FixtureMain
 * ```
 */
@OptIn(ExperimentalSpectreAgentApi::class)
private class LaunchCommand(private val output: Appendable, private val errorOutput: Appendable) :
    CliktCommand(name = "launch") {
    private val workingDirectory: Path? by
        option("-C", "--directory", help = "Working directory for the launched process")
            .path(mustExist = true, canBeFile = false, canBeDir = true)
    private val nameFilter: String? by
        option(
            "--app-name",
            help =
                "Substring of the app JVM display name for Gradle-ish launches " +
                    "(recommended when using ./gradlew)",
        )
    private val once: Boolean by
        option(
                "--once",
                help =
                    "Exit after staged readiness succeeds (print windows count) instead of holding " +
                        "the session open until stdin EOF / interrupt",
            )
            .flag(default = false)
    private val command: List<String> by
        argument(help = "Command to launch; place after -- so flags are not consumed by spectre")
            .multiple(required = true)

    override fun run() {
        if (command.isEmpty()) {
            throw CliktError("launch requires a command after --")
        }
        // Released CLI packages embed agent-runtime; ensure AttachOptions can resolve it.
        installEmbeddedAgentRuntimeForLaunch()
        val spec =
            LaunchSpec(
                command = command,
                workingDirectory = workingDirectory,
                appJvmNameFilter = nameFilter,
            )
        try {
            LaunchAndAttach.launch(spec) { warning ->
                    errorOutput.append(warning)
                    errorOutput.appendLine()
                }
                .use { session ->
                    output.append("launchedPid=${session.launchedPid}")
                    output.append(" attachedPid=${session.attachedPid}")
                    output.append(" gradleish=${session.gradleish}")
                    output.appendLine()
                    output.append("stdout=${session.stdoutPath}")
                    output.appendLine()
                    output.append("stderr=${session.stderrPath}")
                    output.appendLine()
                    val windows = session.automator.windows()
                    output.append("windows=${windows.size}")
                    output.appendLine()
                    if (!once) {
                        output.append(
                            "Session open — press Enter or send EOF to detach and tear down."
                        )
                        output.appendLine()
                        // Hold the session for interactive use; teardown on close.
                        try {
                            System.`in`.read()
                        } catch (_: IOException) {
                            // stdin closed
                        }
                    }
                }
        } catch (ex: LaunchException) {
            errorOutput.append("Spectre launch error [${ex.stage}]: ${ex.message}")
            errorOutput.appendLine()
            throw ProgramResult(EXIT_ATTACH_FAILURE)
        } catch (ex: IOException) {
            errorOutput.append("Spectre launch I/O error: ${ex.message}")
            errorOutput.appendLine()
            throw ProgramResult(EXIT_ATTACH_FAILURE)
        }
    }
}

private fun installEmbeddedAgentRuntimeForLaunch() {
    val prop = "dev.sebastiano.spectre.agent.runtimeJar"
    if (System.getProperty(prop) != null) return
    dev.sebastiano.spectre.cli.daemon.EmbeddedAgentRuntime.install()?.let { runtime ->
        System.setProperty(prop, runtime.toString())
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

@Serializable
private data class DetachJson(
    val version: Int = JSON_VERSION,
    val id: String,
    val captureCount: Int = 0,
    val captureBytes: Long = 0L,
    val capturePaths: List<String> = emptyList(),
    val pruneCommand: String? = null,
    val skillHint: String? = null,
)

@Serializable private data class CompletionJson(val version: Int = JSON_VERSION, val id: String)

@Serializable private data class ScreenshotJson(val version: Int = JSON_VERSION, val path: String)

@Serializable
private data class CaptureJson(
    val version: Int = JSON_VERSION,
    val directory: String,
    val captureJson: String,
    val screenshotPng: String,
    val schemaVersion: Int,
    val windowIndex: Int,
    val nodeCount: Int,
    val taggedNodeCount: Int,
    val textedNodeCount: Int,
    val imageWidth: Int,
    val imageHeight: Int,
    val captureDurationMs: Long,
    val skillHint: String = CaptureSessionReport.CAPTURE_SKILL_NAME,
)

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

internal fun daemonRequest(
    socketPath: Path?,
    attachPreflight: (DaemonRequest) -> String? = ::attachStartupPreflight,
): (DaemonRequest) -> DaemonResponse = daemonRequest@{ request ->
    val resolvedSocketPath = socketPath ?: DaemonEndpoint.defaultSocketPath()
    if (
        socketPath == null &&
            DaemonProtocol.minimumDaemonVersion(request).minor < DaemonProtocol.CurrentVersion.minor
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
        if (request == DaemonRequest.ListSessions) {
            try {
                client.requestIfPresent(request)
            } catch (_: NoSuchFileException) {
                DaemonResponse.Sessions(emptyList())
            }
        } else {
            daemonRequestWithLifecycle(client, resolvedSocketPath, request, attachPreflight)
        }
    }
}

private fun daemonRequestWithLifecycle(
    client: DaemonClient,
    socketPath: Path,
    request: DaemonRequest,
    attachPreflight: (DaemonRequest) -> String?,
): DaemonResponse {
    val launcher = DaemonProcessLauncher(socketPath)
    try {
        return client
            .requestOrStart(
                request = request,
                start = { launcher.start() },
                onAbsent = {
                    attachPreflight(request)?.let { message ->
                        DaemonResponse.Error(DaemonErrorCode.AttachFailed, message)
                    }
                },
            )
            .also { launcher.discardStartupError() }
    } catch (exception: IOException) {
        launcher.consumeStartupError()?.let { diagnostic ->
            throw IOException("Daemon startup failed: $diagnostic", exception)
        }
        throw exception
    }
}

private fun attachStartupPreflight(request: DaemonRequest): String? =
    if (request is DaemonRequest.Attach || request is DaemonRequest.ListJvmProcesses) {
        jdkPreflightError()
    } else {
        null
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

internal class CliOutputException(cause: IOException) : IOException(cause.message, cause)

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
        DaemonErrorCode.OperationFailed,
        DaemonErrorCode.HotReloadUnavailable,
        DaemonErrorCode.ReloadFailed,
        DaemonErrorCode.Timeout -> EXIT_DAEMON_FAILURE
    }
