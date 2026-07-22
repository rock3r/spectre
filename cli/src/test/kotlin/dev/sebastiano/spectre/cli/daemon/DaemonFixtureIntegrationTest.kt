@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.fixture.SPECTRE_FIXTURE_WINDOW_TITLE
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.ImageContent
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import java.awt.GraphicsEnvironment
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** Verifies the daemon owns a real attached agent session across client connections. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class DaemonFixtureIntegrationTest {
    @Test
    fun `MCP stdio drives a Compose fixture through attach tree click and inline screenshot`() =
        runBlocking {
            assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Compose Desktop display")
            val daemonUser = "spectre-mcp-e2e-${UUID.randomUUID()}"
            val process = startMcpBinary(daemonUser)
            val transport =
                StdioClientTransport(
                    input = process.inputStream.asSource().buffered(),
                    output = process.outputStream.asSink().buffered(),
                )

            try {
                spawnComposeFixture().use { fixture ->
                    val client = Client(Implementation(name = "spectre-test", version = "1"))
                    withTimeout(MCP_CONNECTION_TIMEOUT_MILLIS) { client.connect(transport) }
                    val attached =
                        mcpText(client, "attach", mapOf("pid" to fixture.pid)).let { response ->
                            Json.parseToJsonElement(response)
                                .jsonObject
                                .getValue("sessionId")
                                .jsonPrimitive
                                .content
                        }
                    val tree = mcpText(client, "tree", mapOf("session_id" to attached))
                    assertTrue(tree.contains(TAG_LABEL))
                    val button =
                        Json.parseToJsonElement(
                                mcpText(
                                    client,
                                    "find",
                                    mapOf("session_id" to attached, "test_tag" to TAG_BUTTON),
                                )
                            )
                            .jsonObject
                            .getValue("nodes")
                            .jsonArray
                            .single()
                            .jsonObject
                            .getValue("key")
                            .jsonPrimitive
                            .content
                    mcpText(client, "click", mapOf("session_id" to attached, "node_key" to button))
                    val image =
                        client
                            .callTool("screenshot", mapOf("session_id" to attached))
                            .content
                            .single() as ImageContent
                    assertEquals("image/png", image.mimeType)
                    assertTrue(
                        java.util.Base64.getDecoder().decode(image.data).startsWith(PNG_MAGIC)
                    )
                }
            } finally {
                transport.close()
                process.destroyForcibly()
                process.waitFor()
                runCatching { runCliBinary(daemonUser, "daemon", "kill") }
                deleteDaemonSocketAndParent(DaemonEndpoint.defaultSocketPath(userName = daemonUser))
            }
        }

    @Test
    fun `CLI binary drives a Compose fixture through ps attach find click and screenshot`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Compose Desktop display")
        val daemonUser = "spectre-cli-e2e-${UUID.randomUUID()}"
        val screenshot = Files.createTempFile("spectre-cli-e2e-", ".png")

        try {
            spawnComposeFixture().use { fixture ->
                val processes = runCliBinary(daemonUser, "ps", "--json")
                assertEquals(0, processes.exitCode, processes.output + processes.errorOutput)
                assertTrue(
                    Json.parseToJsonElement(processes.output)
                        .jsonObject
                        .getValue("processes")
                        .jsonArray
                        .any { process ->
                            process.jsonObject.getValue("pid").jsonPrimitive.content.toLong() ==
                                fixture.pid
                        }
                )

                val attached = runCliBinary(daemonUser, "attach", fixture.pid.toString(), "--json")
                assertEquals(0, attached.exitCode)
                val sessionId =
                    Json.parseToJsonElement(attached.output)
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content

                val found = runCliBinary(daemonUser, "find", sessionId, TAG_BUTTON, "--json")
                assertEquals(0, found.exitCode)
                val buttonKey =
                    Json.parseToJsonElement(found.output)
                        .jsonObject
                        .getValue("nodes")
                        .jsonArray
                        .single { node ->
                            node.jsonObject.getValue("testTag").jsonPrimitive.content == TAG_BUTTON
                        }
                        .jsonObject
                        .getValue("key")
                        .jsonPrimitive
                        .content

                assertEquals(0, runCliBinary(daemonUser, "click", sessionId, buttonKey).exitCode)
                assertEquals(
                    0,
                    runCliBinary(
                            daemonUser,
                            "screenshot",
                            sessionId,
                            "--output",
                            screenshot.toString(),
                        )
                        .exitCode,
                )
                assertTrue(Files.readAllBytes(screenshot).startsWith(PNG_MAGIC))
            }
        } finally {
            runCatching { runCliBinary(daemonUser, "daemon", "kill") }
            Files.deleteIfExists(screenshot)
            deleteDaemonSocketAndParent(DaemonEndpoint.defaultSocketPath(userName = daemonUser))
        }
    }

    @Test
    fun `daemon attaches to a real Compose fixture and dispatches operations`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Compose Desktop display")
        val socketPath = temporaryDaemonFixtureSocketPath()
        var daemon: Process? = null

        try {
            spawnComposeFixture().use { fixture ->
                DaemonClient(socketPath).use { client ->
                    val attached =
                        client.requestOrStart(DaemonRequest.Attach(fixture.pid)) {
                            daemon =
                                DaemonProcessLauncher(
                                        socketPath = socketPath,
                                        classPath = daemonFixtureRuntimeClassPath(),
                                    )
                                    .start()
                        } as DaemonResponse.Attached

                    assertEquals(fixture.pid, attached.targetPid)
                    assertTrue(
                        (client.request(DaemonRequest.Windows(attached.sessionId))
                                as DaemonResponse.Windows)
                            .windows
                            .any { it.title == SPECTRE_FIXTURE_WINDOW_TITLE }
                    )
                    assertTrue(
                        (client.request(DaemonRequest.FindByTestTag(attached.sessionId, TAG_LABEL))
                                as DaemonResponse.Nodes)
                            .nodes
                            .isNotEmpty()
                    )

                    val button =
                        (client.request(DaemonRequest.FindByTestTag(attached.sessionId, TAG_BUTTON))
                                as DaemonResponse.Nodes)
                            .nodes
                            .first()
                    assertEquals(
                        DaemonResponse.Completed(attached.sessionId),
                        client.request(DaemonRequest.Click(attached.sessionId, button.key)),
                    )

                    val screenshot =
                        client.request(DaemonRequest.Screenshot(attached.sessionId))
                            as DaemonResponse.Screenshot
                    assertTrue(screenshot.pngBytes.size >= MIN_PNG_BYTES)
                    assertTrue(screenshot.pngBytes.startsWith(PNG_MAGIC))
                    assertEquals(
                        DaemonResponse.ShuttingDown,
                        client.request(DaemonRequest.Shutdown),
                    )
                }
            }
        } finally {
            daemon?.destroyForcibly()?.waitFor()
            deleteTemporaryDaemonFixtureSocketPath(socketPath)
        }
    }

    /**
     * #185 kill-target acceptance (real attach): daemon-owned record start → kill fixture mid-
     * record → stop still finalises a non-empty MP4. Skips on headless CI and when Screen Recording
     * is not granted (macOS TCC).
     */
    @Test
    @EnabledOnOs(OS.MAC)
    fun `CLI record stop finalizes after killing the attached target process`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Compose Desktop display")
        assumeTrue(
            runCatching {
                    dev.sebastiano.spectre.recording.screencapturekit.MacOsScreenCaptureAccess
                        .preflight()
                        .granted
                }
                .getOrDefault(false),
            "Screen Recording TCC not granted for this runner",
        )
        val daemonUser = "spectre-record-kill-e2e-${UUID.randomUUID()}"
        val output = Files.createTempFile("spectre-record-kill-", ".mp4")
        Files.deleteIfExists(output)

        try {
            spawnComposeFixture().use { fixture ->
                val attached = runCliBinary(daemonUser, "attach", fixture.pid.toString(), "--json")
                assertEquals(0, attached.exitCode, attached.output + attached.errorOutput)
                val sessionId =
                    Json.parseToJsonElement(attached.output)
                        .jsonObject
                        .getValue("id")
                        .jsonPrimitive
                        .content

                val start =
                    runCliBinary(
                        daemonUser,
                        "record",
                        "start",
                        sessionId,
                        "--output",
                        output.toString(),
                        "--json",
                    )
                assertEquals(0, start.exitCode, start.output + start.errorOutput)
                assertTrue(Files.exists(output) || start.output.contains("mp4"), start.output)

                // Kill the target mid-record; daemon + SCK helper must keep running.
                fixture.close()
                Thread.sleep(RECORD_AFTER_KILL_SETTLE_MILLIS)

                val stop = runCliBinary(daemonUser, "record", "stop", sessionId, "--json")
                // Stop may report helper exit 5 if the window vanished; the file must still exist.
                assertTrue(
                    Files.isRegularFile(output) && Files.size(output) > 0,
                    "expected finalized MP4 after kill+stop; stop.exit=${stop.exitCode} " +
                        "out=${stop.output} err=${stop.errorOutput} size=" +
                        runCatching { Files.size(output) }.getOrDefault(-1),
                )
            }
        } finally {
            runCatching { runCliBinary(daemonUser, "daemon", "kill") }
            Files.deleteIfExists(output)
            deleteDaemonSocketAndParent(DaemonEndpoint.defaultSocketPath(userName = daemonUser))
        }
    }
}

private fun temporaryDaemonFixtureSocketPath(): Path =
    temporaryDaemonFixtureRoot().resolve("daemon").resolve("daemon.sock")

private fun temporaryDaemonFixtureRoot(): Path =
    Path.of(
        if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) "/tmp"
        else System.getProperty("java.io.tmpdir"),
        "sp-d-${UUID.randomUUID().toString().take(8)}",
    )

private fun daemonFixtureRuntimeClassPath(): String =
    requireNotNull(System.getProperty("spectre.cli.testRuntimeClasspath")) {
        "Missing CLI test runtime classpath"
    }

private fun deleteTemporaryDaemonFixtureSocketPath(socketPath: Path) {
    Files.deleteIfExists(socketPath)
    Files.deleteIfExists(socketPath.parent)
    Files.deleteIfExists(socketPath.parent.parent)
}

private fun deleteDaemonSocketAndParent(socketPath: Path) {
    Files.deleteIfExists(socketPath)
    Files.deleteIfExists(socketPath.parent)
}

private fun spawnComposeFixture(): FixtureProcess {
    val javaExe =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe"
        else "java"
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", javaExe).toString()
    val process =
        ProcessBuilder(
                javaBin,
                "-cp",
                System.getProperty("java.class.path"),
                "-XX:+EnableDynamicAgentLoading",
                "-Djava.awt.headless=false",
                "-Dcompose.application.configure.swing.globals=true",
                "-Dapple.awt.UIElement=false",
                "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
            )
            .redirectErrorStream(true)
            .start()
    val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    val ready = CountDownLatch(1)
    val drainer =
        Thread({
                try {
                    generateSequence(reader::readLine).forEach { line ->
                        if (line.startsWith(READY_SENTINEL)) ready.countDown()
                    }
                } catch (_: java.io.IOException) {}
            })
            .apply {
                isDaemon = true
                start()
            }
    check(ready.await(FIXTURE_READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        "Compose fixture did not emit $READY_SENTINEL"
    }
    return FixtureProcess(process, reader, drainer)
}

private fun runCliBinary(daemonUser: String, vararg arguments: String): CliBinaryResult {
    val distributionExecutable = System.getProperty("spectre.cli.distributionExecutable")
    val command =
        distributionExecutable?.let { executable -> listOf(executable, *arguments) }
            ?: run {
                val javaExe =
                    if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                        "java.exe"
                    } else {
                        "java"
                    }
                listOf(
                    Paths.get(System.getProperty("java.home"), "bin", javaExe).toString(),
                    "-Duser.name=$daemonUser",
                    "-Djava.awt.headless=false",
                    "-cp",
                    daemonFixtureRuntimeClassPath(),
                    "dev.sebastiano.spectre.cli.SpectreCliKt",
                    *arguments,
                )
            }
    val process =
        ProcessBuilder(command)
            .apply {
                if (distributionExecutable != null) {
                    // Roast launches the bundled JVM directly rather than through Gradle's
                    // generated start script, so it cannot consume SPECTRE_OPTS. The JVM
                    // itself consumes JAVA_TOOL_OPTIONS before application startup.
                    val daemonHome = Path.of("/tmp", daemonUser)
                    environment()["JAVA_TOOL_OPTIONS"] =
                        "-Duser.name=$daemonUser -Duser.home=$daemonHome -Djava.awt.headless=false"
                }
            }
            .start()
    val output = StringBuilder()
    val errorOutput = StringBuilder()
    val outputDrainer =
        Thread({
                process.inputStream.bufferedReader().use { reader ->
                    output.append(reader.readText())
                }
            })
            .apply {
                isDaemon = true
                start()
            }
    val errorDrainer =
        Thread({
                process.errorStream.bufferedReader().use { reader ->
                    errorOutput.append(reader.readText())
                }
            })
            .apply {
                isDaemon = true
                start()
            }
    if (!process.waitFor(CLI_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        process.waitFor()
        outputDrainer.join()
        errorDrainer.join()
        error(
            "CLI binary did not exit within $CLI_PROCESS_TIMEOUT_SECONDS seconds: " +
                arguments.joinToString()
        )
    }
    outputDrainer.join()
    errorDrainer.join()
    return CliBinaryResult(
        exitCode = process.exitValue(),
        output = output.toString(),
        errorOutput = errorOutput.toString(),
    )
}

private fun startMcpBinary(daemonUser: String): Process {
    val javaExe =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe"
        else "java"
    return ProcessBuilder(
            Paths.get(System.getProperty("java.home"), "bin", javaExe).toString(),
            "-Duser.name=$daemonUser",
            "-Djava.awt.headless=false",
            "-cp",
            daemonFixtureRuntimeClassPath(),
            "dev.sebastiano.spectre.cli.SpectreCliKt",
            "mcp",
        )
        .start()
}

private suspend fun mcpText(client: Client, tool: String, arguments: Map<String, Any?>): String {
    val result = client.callTool(tool, arguments)
    assertTrue(result.isError != true, "MCP $tool failed: ${result.content}")
    return (result.content.single() as TextContent).text
}

private data class CliBinaryResult(val exitCode: Int, val output: String, val errorOutput: String)

private class FixtureProcess(
    private val process: Process,
    private val reader: BufferedReader,
    private val drainer: Thread,
) : AutoCloseable {
    val pid: Long
        get() = process.pid()

    override fun close() {
        process.destroyForcibly()
        process.waitFor(FIXTURE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        drainer.join(DRAINER_JOIN_TIMEOUT_MILLIS)
        runCatching { reader.close() }
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private const val FIXTURE_READY_TIMEOUT_SECONDS: Long = 30
private const val FIXTURE_STOP_TIMEOUT_SECONDS: Long = 2
private const val DRAINER_JOIN_TIMEOUT_MILLIS: Long = 500
private const val CLI_PROCESS_TIMEOUT_SECONDS: Long = 30
private const val MCP_CONNECTION_TIMEOUT_MILLIS: Long = 10_000
private const val RECORD_AFTER_KILL_SETTLE_MILLIS: Long = 1_500
private const val MIN_PNG_BYTES: Int = 100
private val PNG_MAGIC: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
