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
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** Verifies the daemon owns a real attached agent session across client connections. */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class DaemonFixtureIntegrationTest {
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
private const val MIN_PNG_BYTES: Int = 100
private val PNG_MAGIC: ByteArray =
    byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
