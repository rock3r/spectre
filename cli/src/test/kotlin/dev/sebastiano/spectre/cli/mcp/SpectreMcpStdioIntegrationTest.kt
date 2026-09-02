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
import kotlinx.coroutines.TimeoutCancellationException
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
            // Keep stdin open until the initialize response is fully read — closing early can
            // race the SDK writer and drop the JSON-RPC response (see VerifyCliShadowJar).
            val writer = process.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            writer.write(INITIALIZE_REQUEST)
            writer.newLine()
            writer.flush()

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

            writer.close()
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
            // Two budgets, each sized for what it actually covers (#455). Folding a cold JVM
            // start into the protocol budget made this the only one of the three tests in this
            // class that could fail on a slow host, and it did so repeatedly on windows-latest.
            phase("connect", CONNECT_TIMEOUT_MILLIS) { client.connect(transport) }
            phase("protocol exchange", PROTOCOL_TIMEOUT_MILLIS) {
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
                        "detach",
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
                        "wait_until_gone",
                        "wait_for_visual_idle",
                        "wait_for_reload_settled",
                        "windows",
                    ),
                    client.listTools().tools.map { it.name }.toSet(),
                )
                // Tool invocation (attach → op → detach → session-gone) is proven by
                // DaemonFixtureIntegrationTest (including Windows opt-in) and by
                // scripts/mcp-stdio-smoke.py when --attach-pid is provided. Calling
                // list_processes here would auto-start the shared per-user daemon and leave
                // it idle until timeout, which this hermetic protocol test intentionally avoids.
            }
        } finally {
            transport.close()
            process.destroyForcibly()
            process.waitFor()
        }
    }

    /**
     * Run [block] under [millis], reporting *which* phase timed out.
     *
     * A bare `TimeoutCancellationException` says nothing about whether the server failed to start
     * or failed to answer, which is what made #455 hard to read from CI logs alone.
     */
    private suspend fun <T> phase(name: String, millis: Long, block: suspend () -> T): T =
        try {
            withTimeout(millis) { block() }
        } catch (ex: TimeoutCancellationException) {
            throw AssertionError(
                "MCP stdio $name did not complete within ${millis}ms. If this is the connect " +
                    "phase on a loaded runner, suspect cold JVM startup rather than the " +
                    "protocol; the other tests in this class prove the server answers " +
                    "initialize (#455).",
                ex,
            )
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
        /**
         * Hang guard for `connect`, not a performance assertion.
         *
         * This phase spawns `java -cp <full CLI classpath>` and completes the MCP handshake, so the
         * budget has to clear a cold JVM start on the slowest supported runner — on
         * `windows-latest` that happens while Gradle compiles other modules on the same two cores.
         * Locally the whole test takes well under a second; on hosted Windows the old shared 10s
         * budget was exceeded repeatedly (#455). The sibling [PROCESS_EXIT_TIMEOUT_SECONDS] was
         * already raised to 15s for the same startup reason, and this phase does strictly more than
         * that one, so it gets more headroom. A genuine hang still fails the build; the value only
         * decides how long that takes to notice.
         */
        private const val CONNECT_TIMEOUT_MILLIS: Long = 60_000

        /**
         * Budget for the protocol exchange once the server is already up and has answered
         * `initialize`. No process startup in here, so this stays tight enough to catch a real
         * protocol stall.
         */
        private const val PROTOCOL_TIMEOUT_MILLIS: Long = 15_000
        // Windows hosted runners can take longer than the usual process startup window to
        // initialize the JVM before observing closed stdin.
        private const val PROCESS_EXIT_TIMEOUT_SECONDS: Long = 15
        private const val INITIALIZE_REQUEST: String =
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"spectre-test","version":"1"}}}"""
    }
}
