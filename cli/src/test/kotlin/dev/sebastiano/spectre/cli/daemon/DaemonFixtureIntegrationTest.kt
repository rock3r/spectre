@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.fixture.SPECTRE_FIXTURE_WINDOW_TITLE
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** Verifies the daemon owns a real attached agent session across client connections. */
@EnabledOnOs(OS.LINUX, OS.MAC)
class DaemonFixtureIntegrationTest {
    @Test
    fun `CLI binary drives a Compose fixture through ps attach find click and screenshot`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Compose Desktop display")
        val daemonUser = "spectre-cli-e2e-${UUID.randomUUID()}"
        val screenshot = Files.createTempFile("spectre-cli-e2e-", ".png")

        try {
            spawnComposeFixture().use { fixture ->
                val processes = runCliBinary(daemonUser, "ps", "--json")
                assertEquals(0, processes.exitCode)
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
    val javaExe =
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) "java.exe"
        else "java"
    val javaBin = Paths.get(System.getProperty("java.home"), "bin", javaExe).toString()
    val process =
        ProcessBuilder(
                javaBin,
                "-Duser.name=$daemonUser",
                "-Djava.awt.headless=false",
                "-cp",
                daemonFixtureRuntimeClassPath(),
                "dev.sebastiano.spectre.cli.SpectreCliKt",
                *arguments,
            )
            .redirectErrorStream(true)
            .start()
    check(process.waitFor(CLI_PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly()
        "CLI binary did not exit within $CLI_PROCESS_TIMEOUT_SECONDS seconds: ${arguments.joinToString()}"
    }
    val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
    return CliBinaryResult(exitCode = process.exitValue(), output = output)
}

private data class CliBinaryResult(val exitCode: Int, val output: String)

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
private const val MIN_PNG_BYTES: Int = 100
private val PNG_MAGIC: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
