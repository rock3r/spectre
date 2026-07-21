package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.io.Sink
import kotlinx.io.Source
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** MCP stdio facade over the local Spectre session daemon. */
public object SpectreMcpServer {
    /** Creates the MCP server and registers the agent-facing daemon tools. */
    public fun create(request: (DaemonRequest) -> DaemonResponse): Server =
        Server(
                serverInfo = Implementation(name = "spectre", version = MCP_VERSION),
                options =
                    ServerOptions(
                        capabilities = ServerCapabilities(tools = ServerCapabilities.Tools())
                    ),
            )
            .also { server -> registerTools(server, request) }

    /** Runs one long-lived MCP stdio server without writing non-protocol data to stdout. */
    public fun run(
        request: (DaemonRequest) -> DaemonResponse,
        input: Source = System.`in`.asSource().buffered(),
        output: Sink = System.out.asSink().buffered(),
    ): Unit = runBlocking {
        val transportClosed = CompletableDeferred<Unit>()
        val transport = StdioServerTransport(input, output) {}
        transport.onClose { transportClosed.complete(Unit) }

        create(request).createSession(transport)
        transportClosed.await()
    }

    private fun registerTools(server: Server, request: (DaemonRequest) -> DaemonResponse) {
        registerDiscoveryTools(server, request)
        registerSessionTools(server, request)
        registerRecordingTools(server, request)
    }

    private fun registerDiscoveryTools(server: Server, request: (DaemonRequest) -> DaemonResponse) {
        server.addTool(
            name = "list_processes",
            description = "List JVM processes that expose a Spectre agent and can be attached.",
        ) {
            val response = request(DaemonRequest.ListJvmProcesses(ProcessHandle.current().pid()))
            response.asResult { daemonResponse -> daemonResponse is DaemonResponse.JvmProcesses }
        }
        server.addTool(
            name = "attach",
            description =
                "Attach the shared Spectre daemon to a target JVM process and return its session id.",
            inputSchema = schema("pid" to "integer"),
        ) { call ->
            request(DaemonRequest.Attach(call.requiredLong("pid"))).asResult { daemonResponse ->
                daemonResponse is DaemonResponse.Attached
            }
        }
    }

    private fun registerSessionTools(server: Server, request: (DaemonRequest) -> DaemonResponse) {
        server.addTool(
            name = "windows",
            description = "List top-level windows and popup roots for an attached session.",
            inputSchema = sessionSchema(),
        ) { call ->
            request(DaemonRequest.Windows(call.requiredString("session_id"))).asResult {
                it is DaemonResponse.Windows
            }
        }
        server.addTool(
            name = "tree",
            description =
                "Dump the current semantics tree. Node keys are valid only until the UI changes.",
            inputSchema = sessionSchema(),
        ) { call ->
            request(DaemonRequest.AllNodes(call.requiredString("session_id"))).asResult {
                it is DaemonResponse.Nodes
            }
        }
        server.addTool(
            name = "find",
            description =
                "Find semantics nodes by exact Compose test tag. Returned node keys are ephemeral.",
            inputSchema = schema("session_id" to "string", "test_tag" to "string"),
        ) { call ->
            request(
                    DaemonRequest.FindByTestTag(
                        call.requiredString("session_id"),
                        call.requiredString("test_tag"),
                    )
                )
                .asResult { it is DaemonResponse.Nodes }
        }
        server.addTool(
            name = "click",
            description =
                "Click a visible semantics node by its node key. Refresh the tree after UI changes.",
            inputSchema = schema("session_id" to "string", "node_key" to "string"),
        ) { call ->
            request(
                    DaemonRequest.Click(
                        call.requiredString("session_id"),
                        call.requiredString("node_key"),
                    )
                )
                .asResult { it is DaemonResponse.Completed }
        }
        server.addTool(
            name = "type_text",
            description =
                "Type text through the real operating-system input path into the focused target UI.",
            inputSchema = schema("session_id" to "string", "text" to "string"),
        ) { call ->
            request(
                    DaemonRequest.TypeText(
                        call.requiredString("session_id"),
                        call.requiredString("text"),
                    )
                )
                .asResult { it is DaemonResponse.Completed }
        }
        registerScreenshotTool(server, request)
        registerCaptureTool(server, request)
    }

    private fun registerCaptureTool(server: Server, request: (DaemonRequest) -> DaemonResponse) {
        val captureSchema =
            schema(
                    "session_id" to "string",
                    "window_index" to "integer",
                    "out_dir" to "string",
                    "include_image" to "boolean",
                )
                .let { base ->
                    // session_id is required; other params are optional overrides.
                    ToolSchema(properties = base.properties, required = listOf("session_id"))
                }
        server.addTool(
            name = "capture",
            description =
                "Atomic capture: window PNG + full semantics tree written to disk. Returns a " +
                    "decision-grade summary with artifact paths (tree is never inlined). Optional " +
                    "inline PNG preview is available via include_image=true. See the " +
                    "spectre-capture skill for jq recipes over capture.json.",
            inputSchema = captureSchema,
        ) { call ->
            handleCaptureTool(call, request)
        }
    }

    private fun handleCaptureTool(
        call: CallToolRequest,
        request: (DaemonRequest) -> DaemonResponse,
    ): CallToolResult {
        val sessionId = call.requiredString("session_id")
        val windowIndexArg = call.arguments?.get("window_index")?.jsonPrimitive?.content
        val windowIndex =
            if (windowIndexArg == null) {
                0
            } else {
                windowIndexArg.toIntOrNull()
                    ?: return CallToolResult(
                        listOf(
                            TextContent(
                                "MCP tool argument 'window_index' must be an integer, got '$windowIndexArg'."
                            )
                        ),
                        isError = true,
                    )
            }
        val outDir =
            call.arguments
                ?.get("out_dir")
                ?.jsonPrimitive
                ?.content
                ?.let(::normalizeRecordingOutputPath)
        val includeImage =
            call.arguments?.get("include_image")?.jsonPrimitive?.content?.toBooleanStrictOrNull()
                ?: false
        return when (
            val response =
                request(
                    DaemonRequest.Capture(
                        sessionId = sessionId,
                        windowIndex = windowIndex,
                        outDir = outDir,
                    )
                )
        ) {
            is DaemonResponse.Capture -> captureToolResult(response, includeImage)
            is DaemonResponse.Error ->
                CallToolResult(listOf(TextContent(response.message)), isError = true)
            else ->
                CallToolResult(
                    listOf(TextContent("Spectre daemon returned an unexpected response.")),
                    isError = true,
                )
        }
    }

    private fun captureToolResult(
        response: DaemonResponse.Capture,
        includeImage: Boolean,
    ): CallToolResult {
        val summaryText = MCP_JSON.encodeToString(response)
        if (!includeImage) return CallToolResult(listOf(TextContent(summaryText)))
        val pngBytes =
            java.nio.file.Files.readAllBytes(java.nio.file.Path.of(response.screenshotPngPath))
        return CallToolResult(
            listOf(
                TextContent(summaryText),
                ImageContent(
                    data = Base64.getEncoder().encodeToString(pngBytes),
                    mimeType = "image/png",
                ),
            )
        )
    }

    private fun registerRecordingTools(server: Server, request: (DaemonRequest) -> DaemonResponse) {
        server.addTool(
            name = "record_start",
            description =
                "Start daemon-owned recording for an attached session. Default is window index 0. " +
                    "Pass fullscreen=true for a full virtual-desktop region capture (do not combine " +
                    "with window_index). output_path is optional — omit to allocate under the " +
                    "Spectre capture root. Returns the output path only (no video bytes).",
            inputSchema =
                ToolSchema(
                    properties =
                        buildJsonObject {
                            put("session_id", buildJsonObject { put("type", "string") })
                            put("output_path", buildJsonObject { put("type", "string") })
                            put("window_index", buildJsonObject { put("type", "integer") })
                            put("fullscreen", buildJsonObject { put("type", "boolean") })
                        },
                    required = listOf("session_id"),
                ),
        ) { call ->
            val outputRaw =
                call.arguments?.get("output_path")?.jsonPrimitive?.content?.takeIf {
                    it.isNotBlank()
                }
            val windowIndexArg = call.arguments?.get("window_index")?.jsonPrimitive?.content
            val windowIndex =
                if (windowIndexArg == null) {
                    null
                } else {
                    windowIndexArg.toIntOrNull()
                        ?: return@addTool CallToolResult(
                            listOf(
                                TextContent(
                                    "MCP tool argument 'window_index' must be an integer, " +
                                        "got '$windowIndexArg'."
                                )
                            ),
                            isError = true,
                        )
                }
            val fullscreenRaw = call.arguments?.get("fullscreen")?.jsonPrimitive?.content
            val fullscreen =
                parseOptionalBooleanArg(fullscreenRaw)
                    ?: return@addTool CallToolResult(
                        listOf(
                            TextContent(
                                "MCP tool argument 'fullscreen' must be a boolean, got '$fullscreenRaw'."
                            )
                        ),
                        isError = true,
                    )
            if (fullscreen && windowIndex != null) {
                return@addTool CallToolResult(
                    listOf(
                        TextContent("record_start: fullscreen cannot be combined with window_index")
                    ),
                    isError = true,
                )
            }
            request(
                    DaemonRequest.StartRecording(
                        sessionId = call.requiredString("session_id"),
                        outputPath = outputRaw?.let(::normalizeRecordingOutputPath),
                        windowIndex = windowIndex ?: 0,
                        fullscreen = fullscreen,
                    )
                )
                .asResult { it is DaemonResponse.RecordingStarted }
        }
        server.addTool(
            name = "record_stop",
            description = "Stop the active recording and return its final output path.",
            inputSchema = sessionSchema(),
        ) { call ->
            request(DaemonRequest.StopRecording(call.requiredString("session_id"))).asResult {
                it is DaemonResponse.RecordingStopped
            }
        }
        server.addTool(
            name = "record_status",
            description = "Report whether a session has an active recording and its output path.",
            inputSchema = sessionSchema(),
        ) { call ->
            request(DaemonRequest.RecordingStatus(call.requiredString("session_id"))).asResult {
                it is DaemonResponse.RecordingStatus
            }
        }
    }

    private fun schema(vararg properties: Pair<String, String>): ToolSchema =
        ToolSchema(
            properties =
                buildJsonObject {
                    properties.forEach { (name, type) ->
                        put(name, buildJsonObject { put("type", type) })
                    }
                },
            required = properties.map(Pair<String, String>::first),
        )

    private fun sessionSchema(): ToolSchema = schema("session_id" to "string")

    private fun CallToolRequest.callString(name: String): String =
        arguments?.get(name)?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("MCP tool requires a non-empty '$name' argument")

    private fun CallToolRequest.requiredString(name: String): String = callString(name)

    private fun CallToolRequest.requiredLong(name: String): Long =
        requiredString(name).toLongOrNull()
            ?: throw IllegalArgumentException("MCP tool argument '$name' must be an integer")

    private fun DaemonResponse.asResult(expected: (DaemonResponse) -> Boolean): CallToolResult =
        when {
            this is DaemonResponse.Error ->
                CallToolResult(listOf(TextContent(message)), isError = true)
            expected(this) -> CallToolResult(listOf(TextContent(MCP_JSON.encodeToString(this))))
            else ->
                CallToolResult(
                    listOf(TextContent("Spectre daemon returned an unexpected response.")),
                    isError = true,
                )
        }

    private const val MCP_VERSION: String = "0.1.0"
    private val MCP_JSON: Json = Json { encodeDefaults = true }
}

internal fun normalizeRecordingOutputPath(outputPath: String): String =
    java.nio.file.Path.of(outputPath).toAbsolutePath().normalize().toString()

internal fun DaemonResponse.screenshotResult(): CallToolResult =
    when (this) {
        is DaemonResponse.Screenshot ->
            CallToolResult(
                content =
                    listOf(
                        ImageContent(
                            data = Base64.getEncoder().encodeToString(pngBytes),
                            mimeType = "image/png",
                        )
                    )
            )
        is DaemonResponse.Error -> CallToolResult(listOf(TextContent(message)), isError = true)
        else ->
            CallToolResult(
                listOf(TextContent("Spectre daemon did not return a screenshot.")),
                isError = true,
            )
    }

private fun registerScreenshotTool(server: Server, request: (DaemonRequest) -> DaemonResponse) {
    val properties = buildJsonObject {
        put("session_id", buildJsonObject { put("type", "string") })
        put("window_index", buildJsonObject { put("type", "integer") })
        put("surface_id", buildJsonObject { put("type", "string") })
        put("fullscreen", buildJsonObject { put("type", "boolean") })
    }
    server.addTool(
        name = "screenshot",
        description =
            "Capture a tracked window of the attached UI (default: window index 0) and return " +
                "a PNG inline as MCP image content, never as a file path. Pass fullscreen=true " +
                "only when a full virtual-desktop grab is intentional; do not combine fullscreen " +
                "with window_index or surface_id.",
        inputSchema = ToolSchema(properties = properties, required = listOf("session_id")),
    ) { call ->
        handleScreenshotTool(call, request)
    }
}

private fun handleScreenshotTool(
    call: CallToolRequest,
    request: (DaemonRequest) -> DaemonResponse,
): CallToolResult {
    val sessionId =
        call.arguments?.get("session_id")?.jsonPrimitive?.content
            ?: return CallToolResult(
                content =
                    listOf(TextContent("MCP tool requires a non-empty 'session_id' argument")),
                isError = true,
            )
    val windowIndexArg = call.arguments?.get("window_index")?.jsonPrimitive?.content
    val windowIndex =
        if (windowIndexArg == null) {
            null
        } else {
            windowIndexArg.toIntOrNull()
                ?: return CallToolResult(
                    content =
                        listOf(
                            TextContent(
                                "MCP tool argument 'window_index' must be an integer, got '$windowIndexArg'."
                            )
                        ),
                    isError = true,
                )
        }
    val surfaceId = call.arguments?.get("surface_id")?.jsonPrimitive?.content
    val fullscreenRaw = call.arguments?.get("fullscreen")?.jsonPrimitive?.content
    val fullscreen =
        parseOptionalBooleanArg(fullscreenRaw)
            ?: return CallToolResult(
                content =
                    listOf(
                        TextContent(
                            "MCP tool argument 'fullscreen' must be a boolean, got '$fullscreenRaw'."
                        )
                    ),
                isError = true,
            )
    if (fullscreen && (windowIndex != null || surfaceId != null)) {
        return CallToolResult(
            content =
                listOf(
                    TextContent(
                        "screenshot: fullscreen cannot be combined with window_index or surface_id"
                    )
                ),
            isError = true,
        )
    }
    return request(
            DaemonRequest.Screenshot(
                sessionId = sessionId,
                windowIndex = windowIndex,
                surfaceId = surfaceId,
                fullscreen = fullscreen,
            )
        )
        .screenshotResult()
}

/** Returns false when [raw] is null; null when present but not a recognized boolean token. */
private fun parseOptionalBooleanArg(raw: String?): Boolean? =
    when (raw) {
        null -> false
        "true",
        "True",
        "1" -> true
        "false",
        "False",
        "0" -> false
        else -> null
    }
