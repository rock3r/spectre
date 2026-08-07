package dev.sebastiano.spectre.cli.mcp

import dev.sebastiano.spectre.cli.SpectreBuildMetadata
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SpectreMcpStdioIntegrationTest {
    @Test
    fun `spectre mcp exits when its stdio input closes`() {
        val process = ProcessBuilder(mcpCommand()).start()

        try {
            process.outputStream.close()

            assertTrue(process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `spectre mcp stdout stays protocol-clean through initialize`() {
        val process = ProcessBuilder(mcpCommand()).redirectErrorStream(false).start()
        try {
            process.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(INITIALIZE_REQUEST)
                writer.newLine()
                writer.flush()
            }
            // Keep stdin open only long enough for one request/response; then close so the server
            // can shut down after the initialize exchange.
            process.outputStream.close()

            val stdout =
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
            val firstLine =
                assertNotNull(
                    stdout.readLine(),
                    "MCP process produced no stdout line for initialize",
                )
            assertFalse(
                firstLine.contains("kotlin-logging", ignoreCase = true),
                "non-protocol kotlin-logging banner leaked to stdout: $firstLine",
            )
            assertTrue(
                firstLine.trimStart().startsWith("{"),
                "first stdout line must be JSON-RPC, was: $firstLine",
            )
            val response = Json.parseToJsonElement(firstLine).jsonObject
            assertEquals("2.0", response["jsonrpc"]?.jsonPrimitive?.content)
            assertEquals("1", response["id"]?.toString()?.trim('"'))
            val version =
                response["result"]
                    ?.jsonObject
                    ?.get("serverInfo")
                    ?.jsonObject
                    ?.get("version")
                    ?.jsonPrimitive
                    ?.content
            assertEquals(expectedMcpVersion(), version)

            assertTrue(process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        } finally {
            process.destroyForcibly()
            process.waitFor()
        }
    }

    @Test
    fun `spectre mcp serves tools through official stdio client`() = runBlocking {
        val process = ProcessBuilder(mcpCommand()).start()
        val transport =
            StdioClientTransport(
                input = process.inputStream.asSource().buffered(),
                output = process.outputStream.asSink().buffered(),
            )
        try {
            val client = Client(clientInfo = Implementation(name = "spectre-test", version = "1"))
            withTimeout(CONNECTION_TIMEOUT_MILLIS) {
                client.connect(transport)
                assertEquals(
                    expectedMcpVersion(),
                    client.serverVersion?.version,
                    "MCP serverInfo.version must match build metadata, not a stale constant",
                )
                assertEquals(
                    setOf(
                        "attach",
                        "capture",
                        "click",
                        "double_click",
                        "find",
                        "find_text",
                        "list_processes",
                        "long_click",
                        "press_key",
                        "record_start",
                        "record_stop",
                        "record_status",
                        "screenshot",
                        "scroll_wheel",
                        "swipe",
                        "tree",
                        "type_text",
                        "wait_for_node",
                        "wait_for_visual_idle",
                        "wait_for_reload_settled",
                        "windows",
                    ),
                    client.listTools().tools.map { it.name }.toSet(),
                )
                val toolResult = client.callTool(name = "list_processes", arguments = emptyMap())
                assertNotNull(toolResult, "list_processes tool call returned null")
                assertTrue(toolResult.isError != true, "list_processes failed: $toolResult")
            }
        } finally {
            transport.close()
            process.destroyForcibly()
            process.waitFor()
        }
    }

    private fun mcpCommand(): List<String> =
        System.getProperty("spectre.cli.distributionExecutable")?.let { executable ->
            listOf(executable, "mcp")
        }
            ?: listOf(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp",
                System.getProperty("spectre.cli.testRuntimeClasspath"),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                "mcp",
            )

    private fun expectedMcpVersion(): String {
        val fromGradle =
            System.getProperty("spectre.project.version")
                ?: error(
                    "spectre.project.version must be injected by Gradle so version tests " +
                        "cannot pass against a stale hardcoded constant"
                )
        // Build metadata and the Gradle project version must agree; either alone could be
        // mocked or stale without the cross-check.
        assertEquals(fromGradle, SpectreBuildMetadata.version)
        return fromGradle
    }

    private companion object {
        private const val CONNECTION_TIMEOUT_MILLIS: Long = 10_000
        // Windows hosted runners can take longer than the usual process startup window to
        // initialize the JVM before observing closed stdin.
        private const val PROCESS_EXIT_TIMEOUT_SECONDS: Long = 15
        private const val INITIALIZE_REQUEST: String =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"spectre-test","version":"1"}}}"""
    }
}
