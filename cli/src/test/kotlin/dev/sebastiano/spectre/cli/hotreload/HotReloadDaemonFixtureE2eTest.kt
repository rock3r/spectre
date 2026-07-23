@file:OptIn(org.jetbrains.compose.reload.DelicateHotReloadApi::class)

package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.fixture.READY_SENTINEL
import dev.sebastiano.spectre.agent.fixture.TAG_BUTTON
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
import dev.sebastiano.spectre.cli.daemon.DaemonClient
import dev.sebastiano.spectre.cli.daemon.DaemonProcessLauncher
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
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
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jetbrains.compose.reload.core.getBlocking
import org.jetbrains.compose.reload.core.getOrThrow
import org.jetbrains.compose.reload.orchestration.OrchestrationClientRole
import org.jetbrains.compose.reload.orchestration.OrchestrationHandle
import org.jetbrains.compose.reload.orchestration.OrchestrationMessage
import org.jetbrains.compose.reload.orchestration.asChannel
import org.jetbrains.compose.reload.orchestration.connectOrchestrationClient
import org.jetbrains.compose.reload.orchestration.sendBlocking
import org.jetbrains.compose.reload.orchestration.startOrchestrationServer
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * End-to-end proof for epic #210 + #208 coexistence:
 * - real Compose fixture JVM (same as daemon fixture e2e)
 * - real HR orchestration server + Application-role Ping/Ack client
 * - daemon attach discovers `compose.reload.orchestration.port` and becomes reload-aware
 * - tree issues generation-stamped keys; settle wait completes full chain; pre-reload keys fail
 *   closed; post-reload tree/click work
 *
 * Class redefine is still supplied as orchestration messages (same wire as HR) rather than a full
 * `hotRun` recompile — the Spectre surfaces under test are attach, settle, and invalidation.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
@EnabledOnOs(OS.LINUX, OS.MAC)
class HotReloadDaemonFixtureE2eTest {
    @Test
    @Timeout(90)
    fun `daemon attach under HR orchestration settles and invalidates keys`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires a Compose Desktop display")
        val server = startOrchestrationServer()
        val port = server.port.getBlocking(15.seconds).getOrThrow()
        val appScope = applicationScope()
        val appReady = CountDownLatch(1)
        val appJob = startAckingApplicationClient(port, appScope, appReady)
        assertTrue(
            appReady.await(15, TimeUnit.SECONDS),
            "Application-role HR client never connected",
        )
        val socketPath = temporarySocketPath()
        var daemon: Process? = null
        try {
            spawnComposeFixtureWithOrchestrationPort(port).use { fixture ->
                DaemonClient(socketPath).use { client ->
                    val sessionId =
                        attachWithRetry(client, fixture.pid) {
                                daemon =
                                    DaemonProcessLauncher(
                                            socketPath = socketPath,
                                            classPath = daemonRuntimeClasspath(),
                                        )
                                        .start()
                            }
                            .sessionId
                    val preKey = assertStampedPreReloadTree(client, sessionId)
                    // Stamped keys prove the daemon created a reload-aware session; give the
                    // Tooling client a moment to finish connect + fan-out subscribe after attach.
                    Thread.sleep(1_500)
                    awaitSettledAfterBroadcast(client, sessionId, server)
                    assertStaleKeyRejected(client, sessionId, preKey)
                    assertPostReloadClick(client, sessionId)
                }
            }
        } finally {
            appJob.cancel()
            appScope.cancel()
            server.close()
            daemon?.destroyForcibly()
            deleteSocketTree(socketPath)
        }
    }

    private fun assertStampedPreReloadTree(client: DaemonClient, sessionId: String): String {
        val preTree =
            assertIs<DaemonResponse.Nodes>(client.request(DaemonRequest.AllNodes(sessionId)))
        assertTrue(preTree.nodes.isNotEmpty(), "expected fixture tree")
        val preKey = preTree.nodes.first().key
        assertTrue(preKey.startsWith("g0:"), "reload-aware session should stamp keys, got $preKey")
        assertTrue(
            client
                .request(DaemonRequest.FindByTestTag(sessionId, TAG_LABEL))
                .let { assertIs<DaemonResponse.Nodes>(it).nodes }
                .isNotEmpty()
        )
        return preKey
    }

    private fun awaitSettledAfterBroadcast(
        client: DaemonClient,
        sessionId: String,
        server: OrchestrationHandle,
    ) {
        val outcomeRef = AtomicReference<DaemonResponse?>(null)
        val waitDone = CountDownLatch(1)
        Thread(
                {
                    outcomeRef.set(
                        client.request(
                            DaemonRequest.WaitForReloadSettled(
                                sessionId = sessionId,
                                timeoutMs = 30_000,
                            )
                        )
                    )
                    waitDone.countDown()
                },
                "hr-daemon-e2e-wait",
            )
            .start()
        Thread.sleep(300)
        val request = OrchestrationMessage.ReloadClassesRequest()
        server sendBlocking request
        server sendBlocking
            OrchestrationMessage.ReloadClassesResult(
                reloadRequestId = request.messageId,
                isSuccess = true,
            )
        server sendBlocking
            OrchestrationMessage.UIRendered(
                windowId = null,
                reloadRequestId = request.messageId,
                iteration = 1,
            )
        assertTrue(waitDone.await(40, TimeUnit.SECONDS), "waitForReloadSettled hung")
        assertIs<DaemonResponse.Completed>(outcomeRef.get())
    }

    private fun assertStaleKeyRejected(client: DaemonClient, sessionId: String, preKey: String) {
        val stale =
            assertIs<DaemonResponse.Error>(client.request(DaemonRequest.Click(sessionId, preKey)))
        assertEquals("nodeNotFound", stale.category)
    }

    private fun assertPostReloadClick(client: DaemonClient, sessionId: String) {
        val postTree =
            assertIs<DaemonResponse.Nodes>(client.request(DaemonRequest.AllNodes(sessionId)))
        val postKey = postTree.nodes.first().key
        assertTrue(postKey.startsWith("g1:"), "expected generation bump after settle, got $postKey")
        val button =
            assertIs<DaemonResponse.Nodes>(
                    client.request(DaemonRequest.FindByTestTag(sessionId, TAG_BUTTON))
                )
                .nodes
                .first()
        assertIs<DaemonResponse.Completed>(
            client.request(DaemonRequest.Click(sessionId, button.key))
        )
    }

    private fun applicationScope(
        dispatcher: CoroutineDispatcher = Dispatchers.Default
    ): CoroutineScope = CoroutineScope(dispatcher)

    private fun startAckingApplicationClient(
        port: Int,
        scope: CoroutineScope,
        ready: CountDownLatch,
    ): Job = scope.launch {
        val client =
            connectOrchestrationClient(OrchestrationClientRole.Application, port).getOrThrow()
        val channel = client.asChannel()
        ready.countDown()
        try {
            while (true) {
                val message = channel.receiveCatching().getOrNull() ?: break
                if (message is OrchestrationMessage.Ping) {
                    client.send(OrchestrationMessage.Ack(message.messageId))
                }
            }
        } finally {
            channel.cancel()
            client.close()
        }
    }

    private fun spawnComposeFixtureWithOrchestrationPort(port: Int): FixtureProcess {
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
                    "-Dcompose.reload.orchestration.port=$port",
                    "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
                )
                .redirectErrorStream(true)
                .start()
        val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
        val ready = CountDownLatch(1)
        Thread(
                {
                    try {
                        generateSequence(reader::readLine).forEach { line ->
                            if (line.startsWith(READY_SENTINEL)) ready.countDown()
                        }
                    } catch (_: java.io.IOException) {}
                },
                "fixture-ready-drain",
            )
            .apply {
                isDaemon = true
                start()
            }
        check(ready.await(30, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            "Compose fixture did not emit $READY_SENTINEL"
        }
        // READY is printed before the JVM is always attachable.
        Thread.sleep(750)
        check(process.isAlive) {
            process.destroyForcibly()
            "Compose fixture exited after READY"
        }
        return FixtureProcess(process)
    }

    private fun attachWithRetry(
        client: DaemonClient,
        targetPid: Long,
        startDaemon: () -> Unit,
    ): DaemonResponse.Attached {
        var lastError: DaemonResponse.Error? = null
        repeat(8) { attempt ->
            val response =
                client.requestOrStart(DaemonRequest.Attach(targetPid), start = startDaemon)
            when (response) {
                is DaemonResponse.Attached -> return response
                is DaemonResponse.Error -> {
                    lastError = response
                    val retryable =
                        response.message.contains("not ready to participate in attach handshake") ||
                            response.message.contains("No such process") ||
                            response.message.contains("AttachNotSupportedException")
                    if (!retryable || attempt == 7) {
                        error(
                            "daemon attach failed: code=${response.code} message=${response.message}"
                        )
                    }
                    Thread.sleep(400L * (attempt + 1))
                }
                else -> error("unexpected attach response: $response")
            }
        }
        error("attach retry exhausted: ${lastError?.message}")
    }

    private fun daemonRuntimeClasspath(): String =
        requireNotNull(System.getProperty("spectre.cli.testRuntimeClasspath")) {
            "Missing spectre.cli.testRuntimeClasspath"
        }

    private fun temporarySocketPath(): Path {
        val root =
            Path.of(
                if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) "/tmp"
                else System.getProperty("java.io.tmpdir"),
                "sp-hr-e2e-${UUID.randomUUID().toString().take(8)}",
            )
        return root.resolve("daemon").resolve("daemon.sock")
    }

    private fun deleteSocketTree(socketPath: Path) {
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketPath.parent)
        Files.deleteIfExists(socketPath.parent.parent)
    }

    private class FixtureProcess(private val process: Process) : AutoCloseable {
        val pid: Long
            get() = process.pid()

        override fun close() {
            process.destroyForcibly()
            process.waitFor(5, TimeUnit.SECONDS)
        }
    }
}
