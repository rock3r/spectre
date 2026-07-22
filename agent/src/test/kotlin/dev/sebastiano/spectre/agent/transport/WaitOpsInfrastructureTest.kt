@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/** #201: wait ops over multiplexed IPC — success path, timeout taxonomy, cancel while waiting. */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class WaitOpsInfrastructureTest {
    private val udsPath: Path =
        udsBase().resolve("sp-w-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `waitForNode returns matching node`() {
        val node =
            NodeSnapshotDto(
                key = "s:0:1",
                testTag = "Counter",
                texts = listOf("0"),
                role = null,
                contentDescription = null,
                isVisible = true,
                bounds = RectDto(0, 0, 10, 10),
            )
        IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        is AgentRequest.WaitForNode -> {
                            assertEquals("Counter", request.tag)
                            AgentResponse.Nodes(listOf(node))
                        }
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    val resp =
                        client.send(AgentRequest.WaitForNode(tag = "Counter", timeoutMs = 1_000))
                    val nodes = assertIs<AgentResponse.Nodes>(resp)
                    assertEquals(listOf(node), nodes.nodes)
                }
            }
    }

    @Test
    fun `waitForVisualIdle Ok and timeout taxonomy`() {
        val calls = AtomicInteger(0)
        IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        is AgentRequest.WaitForVisualIdle -> {
                            if (calls.getAndIncrement() == 0) {
                                AgentResponse.Ok
                            } else {
                                AgentResponse.Error(
                                    message = "waitForVisualIdle timed out",
                                    category = AgentErrorCategory.Timeout.wireName,
                                )
                            }
                        }
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    assertEquals(
                        AgentResponse.Ok,
                        client.send(AgentRequest.WaitForVisualIdle(timeoutMs = 500)),
                    )
                    val err =
                        assertIs<AgentResponse.Error>(
                            client.send(AgentRequest.WaitForVisualIdle(timeoutMs = 100))
                        )
                    assertEquals(AgentErrorCategory.Timeout.wireName, err.category)
                }
            }
    }

    @Test
    fun `slow waitForNode can be cancelled`() {
        val started = CountDownLatch(1)
        IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        is AgentRequest.WaitForNode -> {
                            started.countDown()
                            try {
                                Thread.sleep(30_000)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            AgentResponse.Nodes(emptyList())
                        }
                        is AgentRequest.Cancel -> AgentResponse.Ok
                        AgentRequest.Ping -> AgentResponse.Pong
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    val holder = arrayOfNulls<AgentResponse>(1)
                    val t = Thread {
                        holder[0] =
                            client.send(AgentRequest.WaitForNode(tag = "x", timeoutMs = 25_000))
                    }
                    t.isDaemon = true
                    t.start()
                    assertTrue(started.await(3, TimeUnit.SECONDS))
                    client.cancel(1L)
                    assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
                    t.join(5_000)
                    val err = assertIs<AgentResponse.Error>(holder[0])
                    assertEquals(AgentErrorCategory.Cancelled.wireName, err.category)
                }
            }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS path $path did not appear within ${timeoutMs}ms")
    }

    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
            Path.of(System.getProperty("java.io.tmpdir"))
        else Path.of("/tmp")
}
