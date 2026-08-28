@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `ordinary EOF accepts a replacement client while input finishes`() {
        val inputStarted = CountDownLatch(1)
        val allowInputToFinish = CountDownLatch(1)
        val inputFinished = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        is AgentRequest.Click -> {
                            inputStarted.countDown()
                            try {
                                var finished = false
                                while (!finished) {
                                    try {
                                        allowInputToFinish.await()
                                        finished = true
                                    } catch (_: InterruptedException) {
                                        // Model an input call that cannot stop at interruption.
                                    }
                                }
                                AgentResponse.Ok
                            } finally {
                                inputFinished.countDown()
                            }
                        }
                        AgentRequest.Ping -> AgentResponse.Pong
                        else -> AgentResponse.Ok
                    }
                },
                onDetach = { detached.countDown() },
            )
        val replacementExecutor = Executors.newSingleThreadExecutor()
        server.use {
            awaitSocket(udsPath)
            val firstClient = IpcClient(udsPath)
            try {
                val inputThread =
                    Thread {
                            runCatching {
                                firstClient.send(AgentRequest.Click(nodeKey = "target-node"))
                            }
                        }
                        .apply {
                            isDaemon = true
                            start()
                        }
                assertTrue(inputStarted.await(3, TimeUnit.SECONDS), "input op never started")

                firstClient.close()
                val replacement =
                    replacementExecutor.submit<AgentResponse> {
                        IpcClient(udsPath).use {
                            assertEquals(AgentResponse.Pong, it.send(AgentRequest.Ping))
                            it.send(AgentRequest.Detach)
                        }
                    }

                assertFalse(
                    inputFinished.await(200, TimeUnit.MILLISECONDS),
                    "ordinary EOF should not abandon the still-running input operation",
                )
                val early =
                    runCatching { replacement.get(200, TimeUnit.MILLISECONDS) }.exceptionOrNull()
                assertIs<TimeoutException>(
                    early,
                    "replacement handshake/session must wait for orphaned input, not fail: $early",
                )
                allowInputToFinish.countDown()
                assertEquals(AgentResponse.Detached, replacement.get(3, TimeUnit.SECONDS))
                assertTrue(detached.await(3, TimeUnit.SECONDS))
                inputThread.join(3_000)
            } finally {
                allowInputToFinish.countDown()
                assertTrue(
                    inputFinished.await(3, TimeUnit.SECONDS),
                    "input operation did not finish during cleanup",
                )
                assertTrue(
                    detached.await(3, TimeUnit.SECONDS),
                    "detach did not finish after the orphaned input operation",
                )
                firstClient.close()
                replacementExecutor.shutdownNow()
            }
        }
    }

    @Test
    fun `pre-handshake rejection waits for input orphaned by ordinary EOF`() {
        val inputStarted = CountDownLatch(1)
        val allowInputToFinish = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    if (request is AgentRequest.Click) {
                        inputStarted.countDown()
                        while (true) {
                            try {
                                allowInputToFinish.await()
                                break
                            } catch (_: InterruptedException) {
                                // Model native input that outlives interruption.
                            }
                        }
                    }
                    AgentResponse.Ok
                },
                onDetach = { detached.countDown() },
            )
        server.use {
            awaitSocket(udsPath)
            val firstClient = IpcClient(udsPath)
            val inputThread =
                Thread {
                        runCatching {
                            firstClient.send(AgentRequest.Click(nodeKey = "target-node"))
                        }
                    }
                    .apply {
                        isDaemon = true
                        start()
                    }
            try {
                assertTrue(inputStarted.await(3, TimeUnit.SECONDS), "input op never started")
                firstClient.close()

                SocketChannel.open(StandardProtocolFamily.UNIX).use { replacement ->
                    replacement.connect(UnixDomainSocketAddress.of(udsPath))
                    val input = Channels.newInputStream(replacement)
                    val output = Channels.newOutputStream(replacement)
                    Framing.writeFrame(output, WireCodec.encode(AgentRequest.Ping))
                    assertIs<AgentResponse.Error>(
                        WireCodec.decodeResponse(Framing.readFrame(input) ?: error("no response"))
                    )
                }

                assertFalse(
                    detached.await(200, TimeUnit.MILLISECONDS),
                    "handshake rejection released resources while orphaned input was active",
                )
            } finally {
                allowInputToFinish.countDown()
                assertTrue(detached.await(3, TimeUnit.SECONDS))
                inputThread.join(3_000)
                firstClient.close()
            }
        }
    }

    @Test
    fun `detach retains target input resources until active input finishes`() {
        val inputStarted = CountDownLatch(1)
        val allowInputToFinish = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val server =
            IpcServer(
                udsPath,
                AgentRequestHandler { request ->
                    when (request) {
                        is AgentRequest.Click -> {
                            inputStarted.countDown()
                            var finished = false
                            while (!finished) {
                                try {
                                    allowInputToFinish.await()
                                    finished = true
                                } catch (_: InterruptedException) {
                                    // A native input call can outlive interruption. Model that
                                    // contract so detach must retain coordination until return.
                                }
                            }
                            AgentResponse.Ok
                        }
                        else -> AgentResponse.Ok
                    }
                },
                onDetach = { detached.countDown() },
            )
        val detachExecutor = Executors.newSingleThreadExecutor()
        try {
            server.use {
                awaitSocket(udsPath)
                IpcClient(udsPath).use { client ->
                    val inputThread =
                        Thread {
                                runCatching {
                                    client.send(AgentRequest.Click(nodeKey = "target-node"))
                                }
                            }
                            .apply {
                                isDaemon = true
                                start()
                            }

                    assertTrue(inputStarted.await(3, TimeUnit.SECONDS), "input op never started")
                    val detach =
                        detachExecutor.submit<AgentResponse> { client.send(AgentRequest.Detach) }
                    try {
                        assertFailsWith<TimeoutException> { detach.get(200, TimeUnit.MILLISECONDS) }
                    } finally {
                        allowInputToFinish.countDown()
                    }

                    assertEquals(AgentResponse.Detached, detach.get(3, TimeUnit.SECONDS))
                    assertTrue(
                        detached.await(3, TimeUnit.SECONDS),
                        "detach did not release target resources after input finished",
                    )
                    inputThread.join(3_000)
                }
            }
        } finally {
            detachExecutor.shutdownNow()
        }
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
