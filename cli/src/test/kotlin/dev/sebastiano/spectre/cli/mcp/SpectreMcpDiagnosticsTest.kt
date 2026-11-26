package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.daemon.AccessibilityError
import dev.sebastiano.spectre.cli.daemon.AccessibilityErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonClient
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonServer
import dev.sebastiano.spectre.cli.daemon.DaemonSessionRegistry
import dev.sebastiano.spectre.cli.daemon.RequestDiagnostics
import dev.sebastiano.spectre.cli.daemon.TestDaemonSessionAutomator
import dev.sebastiano.spectre.cli.daemon.deleteTemporaryDaemonClientSocketPath
import dev.sebastiano.spectre.cli.daemon.temporaryDaemonClientSocketPath
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SpectreMcpDiagnosticsTest {
    @Test
    fun `accessibility structured failures are never MCP success`() = runBlocking {
        val server = SpectreMcpServer.create {
            DaemonResponse.AccessibilityErrorResult(
                AccessibilityError(
                    AccessibilityErrorCode.Unknown,
                    "reader is busy",
                    rawCode = "reader-busy",
                )
            )
        }

        val result = invokeTool(server, "accessibility_tree", buildJsonObject { put("pid", 42) })

        assertTrue(result.isError == true, "structured helper failure must set isError: $result")
        val body = Json.parseToJsonElement((result.content.single() as TextContent).text).jsonObject
        assertEquals("reader-busy", body.getValue("rawCode").jsonPrimitive.content)
    }

    @Test
    fun `real daemon wire carries warnings into generic image and partial MCP results`() =
        runBlocking {
            val socketPath = temporaryDaemonClientSocketPath()
            val registry = DaemonSessionRegistry {
                TestDaemonSessionAutomator(
                    nodesResult = {
                        RequestDiagnostics.report("tree degraded")
                        emptyList()
                    },
                    screenshotResult = { _, _, _ ->
                        repeat(101) { RequestDiagnostics.report("screenshot warning $it") }
                        RequestDiagnostics.report("x".repeat(5_000))
                        byteArrayOf(1, 2, 3)
                    },
                    windowsResult = {
                        RequestDiagnostics.report("windows partial failure")
                        error("window provider failed")
                    },
                )
            }
            val daemon = DaemonServer(socketPath, registry)
            try {
                val client = DaemonClient(socketPath, retainDiagnostics = true)
                assertIs<DaemonResponse.Attached>(client.request(DaemonRequest.Attach(42)))
                val server = SpectreMcpServer.create(client::request)
                val arguments = buildJsonObject { put("session_id", "pid-42") }

                val tree = invokeTool(server, "tree", arguments)
                assertEquals("warning: tree degraded", (tree.content.first() as TextContent).text)
                assertTrue(tree.isError != true)

                val screenshot = invokeTool(server, "screenshot", arguments)
                assertEquals(101, screenshot.content.size)
                assertEquals(
                    (0..98).map { "warning: screenshot warning $it" },
                    screenshot.content.dropLast(2).map { (it as TextContent).text },
                )
                assertEquals(
                    "warning: [spectre-daemon] diagnostics truncated (discarded 3 warning(s) " +
                        "and 904 warning character(s)); recent diagnostics may remain in the " +
                        "retained suffix of the per-socket daemon log",
                    (screenshot.content[99] as TextContent).text,
                )
                assertEquals("AQID", assertIs<ImageContent>(screenshot.content.last()).data)
                assertTrue(screenshot.isError != true)

                val partial = invokeTool(server, "windows", arguments)
                assertEquals(
                    "warning: windows partial failure",
                    (partial.content.first() as TextContent).text,
                )
                assertTrue(partial.isError == true, "partial operation failure must be nonzero")
            } finally {
                daemon.close()
                daemon.awaitTermination()
                deleteTemporaryDaemonClientSocketPath(socketPath)
            }
        }
}
