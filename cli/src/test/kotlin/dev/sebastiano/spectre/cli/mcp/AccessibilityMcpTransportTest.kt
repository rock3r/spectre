package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.AccessibilityError
import dev.sebastiano.spectre.cli.daemon.AccessibilityErrorCode
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
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class AccessibilityMcpTransportTest {
    @Test
    fun `accessibility errors stay structured failures through both MCP entry points`() =
        runBlocking {
            val tools =
                listOf(
                    "accessibility_tree" to buildJsonObject { put("pid", 42) },
                    "capture" to
                        buildJsonObject {
                            put("session_id", "pid-42")
                            put("source", "accessibility")
                        },
                )
            val errors =
                listOf(
                    AccessibilityError(
                        AccessibilityErrorCode.Unknown,
                        "reader is busy",
                        rawCode = "reader-busy",
                    ) to "reader-busy",
                    AccessibilityError(AccessibilityErrorCode.RestoreFailed, "restore denied") to
                        "restore-failed",
                )
            tools.forEach { (tool, arguments) ->
                errors.forEach { (error, expectedCode) ->
                    val server = SpectreMcpServer.create {
                        DaemonResponse.AccessibilityErrorResult(error)
                    }

                    val result = invokeTool(server, tool, arguments)

                    assertTrue(result.isError == true, "$tool helper failure must set isError")
                    val body =
                        Json.parseToJsonElement((result.content.single() as TextContent).text)
                            .jsonObject
                    val transportedCode = body["rawCode"] ?: body.getValue("code")
                    assertEquals(expectedCode, transportedCode.jsonPrimitive.content)
                }
            }
        }

    private suspend fun invokeTool(
        server: io.modelcontextprotocol.kotlin.sdk.server.Server,
        name: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ): CallToolResult {
        val registered = assertNotNull(server.tools[name], "tool $name must be registered")
        return registered.handler.invoke(
            UnusedClientConnection,
            CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
        )
    }

    private object UnusedClientConnection : ClientConnection {
        override val sessionId: String = "unit-test"

        override suspend fun notification(
            notification: ServerNotification,
            relatedRequestId: RequestId?,
        ) = Unit

        override suspend fun ping(request: PingRequest, options: RequestOptions?) = EmptyResult()

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
