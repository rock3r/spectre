@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * #200 acceptance: a deliberately slow op stays in flight while cancel / a second quick op still
 * complete promptly, with taxonomy `cancelled` (not a connection error).
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class LongOpInfrastructureTest {
    private val udsPath: Path =
        udsBase().resolve("sp-lo-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `slow op can be cancelled while a quick op still completes`() {
        val slowStarted = CountDownLatch(1)
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        AgentRequest.Ping -> AgentResponse.Pong
                        AgentRequest.Windows -> {
                            slowStarted.countDown()
                            try {
                                Thread.sleep(30_000)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            AgentResponse.Windows(emptyList())
                        }
                        is AgentRequest.Cancel -> AgentResponse.Ok
                        else -> AgentResponse.Ok
                    }
                },
            )
        server.use {
            awaitSocket(udsPath)
            IpcClient(udsPath).use { client ->
                val resultHolder = arrayOfNulls<AgentResponse>(1)
                val slowThread = Thread { resultHolder[0] = client.send(AgentRequest.Windows) }
                slowThread.isDaemon = true
                slowThread.start()

                assertTrue(slowStarted.await(3, TimeUnit.SECONDS), "slow op never started")
                // First post-handshake op is opId=1.
                client.cancel(1L)

                val quick = client.send(AgentRequest.Ping)
                assertEquals(AgentResponse.Pong, quick)

                slowThread.join(5_000)
                val err = assertIs<AgentResponse.Error>(resultHolder[0])
                assertEquals(AgentErrorCategory.Cancelled.wireName, err.category)
            }
        }
    }

    @Test
    fun `deadline elapsed returns timeout category`() {
        IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        AgentRequest.Ping -> AgentResponse.Pong
                        else -> AgentResponse.Ok
                    }
                },
            )
            .use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    val past = System.currentTimeMillis() - 1_000
                    val response = client.send(AgentRequest.Windows, deadlineEpochMs = past)
                    val err = assertIs<AgentResponse.Error>(response)
                    assertEquals(AgentErrorCategory.Timeout.wireName, err.category)
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
