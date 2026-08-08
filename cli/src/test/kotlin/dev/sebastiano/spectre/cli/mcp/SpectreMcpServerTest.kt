package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.SpectreBuildMetadata
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SpectreMcpServerTest {
    @Test
    fun `normalizes recording output paths before sending them to the daemon`() {
        val outputPath = "captures/../captures/session.mp4"

        assertEquals(
            Path.of(outputPath).toAbsolutePath().normalize().toString(),
            normalizeRecordingOutputPath(outputPath),
        )
    }

    @Test
    fun `MCP serverInfo version is derived from project build metadata`() {
        val projectVersion =
            System.getProperty("spectre.project.version")
                ?: error(
                    "spectre.project.version must be injected by Gradle so this test cannot " +
                        "pass against a stale hardcoded MCP version constant"
                )
        // Cross-check resource metadata against the live Gradle project version. A hardcoded
        // "0.1.0" cannot satisfy both when VERSION_NAME is 0.1.0-SNAPSHOT or a release like 0.5.0.
        assertEquals(projectVersion, SpectreBuildMetadata.version)
        assertEquals(projectVersion, SpectreMcpServer.serverVersion())
        assertTrue(
            projectVersion.isNotBlank() && !projectVersion.contains("\${"),
            "project version metadata must be a concrete VERSION_NAME value, was: $projectVersion",
        )
    }

    @Test
    fun `advertises agent-oriented schemas for the initial daemon tool set`() {
        val server =
            SpectreMcpServer.create(request = { error("tools must not run while being listed") })

        assertEquals(
            setOf(
                "list_processes",
                "attach",
                "detach",
                "windows",
                "tree",
                "find",
                "find_text",
                "wait_for_node",
                "wait_for_visual_idle",
                "wait_for_reload_settled",
                "click",
                "double_click",
                "long_click",
                "swipe",
                "scroll_wheel",
                "press_key",
                "type_text",
                "screenshot",
                "capture",
                "record_start",
                "record_stop",
                "record_status",
            ),
            server.tools.keys,
        )
        assertTrue(server.tools.getValue("click").tool.description.orEmpty().contains("node key"))
        assertEquals(
            listOf("session_id", "node_key"),
            server.tools.getValue("click").tool.inputSchema.required,
        )
        assertEquals(
            listOf("session_id"),
            server.tools.getValue("detach").tool.inputSchema.required,
        )
        assertTrue(
            server.tools
                .getValue("detach")
                .tool
                .description
                .orEmpty()
                .contains("session", ignoreCase = true),
            "detach description should mention session cleanup",
        )
        assertTrue(
            server.tools
                .getValue("screenshot")
                .tool
                .description
                .orEmpty()
                .contains("inline", ignoreCase = true)
        )
    }

    @Test
    fun `detach tool returns Detached cleanup summary JSON via daemon protocol`() = runBlocking {
        val seen = mutableListOf<DaemonRequest>()
        val server = SpectreMcpServer.create { request ->
            seen += request
            when (request) {
                is DaemonRequest.Detach ->
                    DaemonResponse.Detached(
                        sessionId = request.sessionId,
                        captureCount = 2,
                        captureBytes = 4_096L,
                        capturePaths =
                            listOf(
                                "/tmp/spectre/captures/session-42/a",
                                "/tmp/spectre/captures/session-42/b",
                            ),
                        pruneCommand = "spectre captures prune --session ${request.sessionId}",
                        skillHint = "spectre-capture",
                    )
                else -> error("unexpected daemon request: $request")
            }
        }

        val result =
            invokeTool(server, "detach", buildJsonObject { put("session_id", "session-42") })

        assertTrue(result.isError != true, "detach success must not set isError: $result")
        val text = (result.content.single() as TextContent).text
        val body = Json.parseToJsonElement(text).jsonObject
        assertEquals("session-42", body.getValue("sessionId").jsonPrimitive.content)
        assertEquals("2", body.getValue("captureCount").jsonPrimitive.content)
        assertEquals("4096", body.getValue("captureBytes").jsonPrimitive.content)
        assertEquals(
            listOf("/tmp/spectre/captures/session-42/a", "/tmp/spectre/captures/session-42/b"),
            body.getValue("capturePaths").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            "spectre captures prune --session session-42",
            body.getValue("pruneCommand").jsonPrimitive.content,
        )
        assertEquals("spectre-capture", body.getValue("skillHint").jsonPrimitive.content)

        val detachRequest = assertIs<DaemonRequest.Detach>(seen.single())
        assertEquals("session-42", detachRequest.sessionId)
    }

    @Test
    fun `detach tool fails closed for unknown session`() = runBlocking {
        val server = SpectreMcpServer.create { request ->
            when (request) {
                is DaemonRequest.Detach ->
                    DaemonResponse.Error(
                        code = DaemonErrorCode.SessionNotFound,
                        message = "session not found: ${request.sessionId}",
                    )
                else -> error("unexpected daemon request: $request")
            }
        }

        val result =
            invokeTool(server, "detach", buildJsonObject { put("session_id", "missing-session") })

        assertTrue(result.isError == true, "unknown session must set isError")
        assertEquals(
            "session not found: missing-session",
            (result.content.single() as TextContent).text,
        )
    }

    @Test
    fun `detach tool requires session_id`() = runBlocking {
        val server = SpectreMcpServer.create {
            error("detach must not reach daemon without session_id")
        }

        val result = invokeTool(server, "detach", buildJsonObject {})

        assertTrue(result.isError == true, "missing session_id must fail closed: $result")
        val message = (result.content.single() as TextContent).text
        assertTrue(
            message.contains("session_id", ignoreCase = true),
            "error should mention session_id, was: $message",
        )
    }

    @Test
    fun `screenshot tool returns daemon PNG bytes as inline MCP image content`() {
        val result =
            DaemonResponse.Screenshot("session-42", byteArrayOf(1, 2, 3)).screenshotResult()

        val image = result.content.single() as ImageContent
        assertEquals("image/png", image.mimeType)
        assertEquals("AQID", image.data)
    }

    @Test
    fun `screenshot tool preserves daemon error messages`() {
        val result =
            DaemonResponse.Error(
                    DaemonErrorCode.SessionNotFound,
                    "session session-42 was not found",
                )
                .screenshotResult()

        assertTrue(result.isError == true)
        assertEquals(
            "session session-42 was not found",
            (result.content.single() as TextContent).text,
        )
    }

    private suspend fun invokeTool(
        server: io.modelcontextprotocol.kotlin.sdk.server.Server,
        name: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ): CallToolResult {
        val registered = assertNotNull(server.tools[name], "tool $name must be registered")
        // Mirror SDK handleCallTool: tool-body failures become isError results so unit tests
        // exercise the same surface official clients see over stdio.
        return try {
            registered.handler.invoke(
                UnusedClientConnection,
                CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
            )
        } catch (error: Exception) {
            CallToolResult(
                content = listOf(TextContent("Error executing tool $name: ${error.message}")),
                isError = true,
            )
        }
    }

    /**
     * Tool handlers for Spectre never use the [ClientConnection] receiver; this stub only exists so
     * unit tests can invoke the registered suspend extension without a live session.
     */
    private object UnusedClientConnection : ClientConnection {
        override val sessionId: String = "unit-test"

        override suspend fun notification(
            notification: ServerNotification,
            relatedRequestId: RequestId?,
        ) = Unit

        override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult =
            EmptyResult()

        override suspend fun createMessage(
            request: CreateMessageRequest,
            options: RequestOptions?,
        ): CreateMessageResult = error("unused")

        override suspend fun listRoots(
            request: ListRootsRequest,
            options: RequestOptions?,
        ): ListRootsResult = error("unused")

        override suspend fun createElicitation(
            request: ElicitRequest,
            options: RequestOptions?,
        ): ElicitResult = error("unused")

        override suspend fun createElicitation(
            message: String,
            requestedSchema: ElicitRequestParams.RequestedSchema,
            options: RequestOptions?,
        ): ElicitResult = error("unused")

        override suspend fun createElicitation(
            message: String,
            elicitationId: String,
            url: String,
            options: RequestOptions?,
        ): ElicitResult = error("unused")

        override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = Unit

        override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = Unit

        override suspend fun sendResourceListChanged() = Unit

        override suspend fun sendToolListChanged() = Unit

        override suspend fun sendPromptListChanged() = Unit

        override suspend fun sendElicitationComplete(
            notification: ElicitationCompleteNotification
        ) = Unit
    }
}
