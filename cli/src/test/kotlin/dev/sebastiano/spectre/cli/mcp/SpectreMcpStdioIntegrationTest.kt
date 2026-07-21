package dev.sebastiano.spectre.cli.mcp

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered

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
                    setOf(
                        "attach",
                        "capture",
                        "click",
                        "find",
                        "list_processes",
                        "record_start",
                        "record_stop",
                        "record_status",
                        "screenshot",
                        "tree",
                        "type_text",
                        "windows",
                    ),
                    client.listTools().tools.map { it.name }.toSet(),
                )
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

    private companion object {
        private const val CONNECTION_TIMEOUT_MILLIS: Long = 10_000
        // Windows hosted runners can take longer than the usual process startup window to
        // initialize the JVM before observing closed stdin.
        private const val PROCESS_EXIT_TIMEOUT_SECONDS: Long = 15
    }
}
