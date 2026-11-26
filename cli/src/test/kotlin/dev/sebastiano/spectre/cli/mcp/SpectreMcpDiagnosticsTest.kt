package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.AccessibilityError
import dev.sebastiano.spectre.cli.daemon.AccessibilityErrorCode
import dev.sebastiano.spectre.cli.daemon.AccessibilityNode
import dev.sebastiano.spectre.cli.daemon.AccessibilitySnapshot
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SpectreMcpDiagnosticsTest {
    private val partialSnapshot =
        AccessibilitySnapshot(
            schemaVersion = 1,
            source = "test-helper",
            capturedAt = "2026-11-26T00:00:00Z",
            windows = emptyList(),
            nodes =
                listOf(
                    AccessibilityNode(
                        key = "node-1",
                        parentKey = null,
                        id = "submit",
                        platformRole = "AXButton",
                        role = "button",
                        name = "Submit",
                        states = listOf("enabled"),
                        value = null,
                        bounds = null,
                        onScreen = true,
                    )
                ),
            notes = listOf("partial traversal"),
        )

    @Test
    fun `accessibility tool unwraps warning handshake around every typed response`() = runBlocking {
        val responses =
            listOf(
                DaemonResponse.AccessibilitySnapshotResult(partialSnapshot) to false,
                DaemonResponse.AccessibilityErrorResult(
                    AccessibilityError(
                        AccessibilityErrorCode.LimitReached,
                        "limit reached",
                        partialSnapshot,
                    )
                ) to true,
                DaemonResponse.Error(DaemonErrorCode.OperationFailed, "helper crashed") to true,
            )

        responses.forEach { (response, expectedError) ->
            val server = SpectreMcpServer.create {
                DaemonResponse.Diagnosed(response, listOf("hello warning", "operation warning"))
            }
            val result =
                invokeTool(server, "accessibility_tree", buildJsonObject { put("pid", 42) })

            assertEquals(expectedError, result.isError == true)
            assertEquals(
                listOf("warning: hello warning", "warning: operation warning"),
                result.content.take(2).map { assertIs<TextContent>(it).text },
            )
            val payload = assertIs<TextContent>(result.content[2]).text
            when (response) {
                is DaemonResponse.AccessibilitySnapshotResult ->
                    assertEquals(Json.encodeToString(response.snapshot), payload)
                is DaemonResponse.AccessibilityErrorResult ->
                    assertEquals(Json.encodeToString(response.error), payload)
                else -> assertEquals("helper crashed", payload)
            }
        }
    }

    @Test
    fun `capture tool unwraps warning handshake for success and both error types`() = runBlocking {
        val png = Files.createTempFile("spectre-mcp-capture", ".png")
        Files.write(png, byteArrayOf(1, 2, 3))
        try {
            val capture =
                DaemonResponse.Capture(
                    "pid-42",
                    png.parent.toString(),
                    "capture.json",
                    png.toString(),
                    1,
                    0,
                    1,
                    1,
                    1,
                    10,
                    20,
                    30,
                )
            val responses =
                listOf(
                    capture to false,
                    DaemonResponse.AccessibilityErrorResult(
                        AccessibilityError(
                            AccessibilityErrorCode.Timeout,
                            "timed out",
                            partialSnapshot,
                        )
                    ) to true,
                    DaemonResponse.Error(DaemonErrorCode.OperationFailed, "capture failed") to true,
                )
            responses.forEach { (response, expectedError) ->
                val server = SpectreMcpServer.create {
                    DaemonResponse.Diagnosed(response, listOf("hello warning", "capture warning"))
                }
                val result =
                    invokeTool(
                        server,
                        "capture",
                        buildJsonObject {
                            put("session_id", "pid-42")
                            put("source", "accessibility")
                            put("include_image", true)
                        },
                    )

                assertEquals(expectedError, result.isError == true)
                assertEquals(
                    listOf("warning: hello warning", "warning: capture warning"),
                    result.content.take(2).map { assertIs<TextContent>(it).text },
                )
                when (response) {
                    is DaemonResponse.Capture ->
                        assertEquals("AQID", assertIs<ImageContent>(result.content.last()).data)
                    is DaemonResponse.AccessibilityErrorResult ->
                        assertEquals(
                            Json.encodeToString(response.error),
                            assertIs<TextContent>(result.content[2]).text,
                        )
                    else ->
                        assertEquals(
                            "capture failed",
                            assertIs<TextContent>(result.content[2]).text,
                        )
                }
            }
        } finally {
            Files.deleteIfExists(png)
        }
    }
}
