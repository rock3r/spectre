@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Regression tests for #168: bounded frame I/O so a same-UID wedged peer cannot hang the serial
 * attach path forever.
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class IpcIoTimeoutTest {
    private val udsPath: Path =
        udsBase().resolve("sp-to-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `healthy Ping Pong still succeeds with frame I O deadlines enabled`() {
        IpcServer(udsPath, stubHandler(), frameIoTimeoutMs = 5_000).use {
            awaitSocket(udsPath)
            IpcClient(udsPath, frameIoTimeoutMs = 5_000).use { client ->
                assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
            }
        }
    }

    @Test
    fun `server times out when client stalls mid-frame and keeps accepting`() {
        val frameTimeoutMs = 250L
        IpcServer(udsPath, stubHandler(), frameIoTimeoutMs = frameTimeoutMs).use {
            awaitSocket(udsPath)

            // Write a partial frame header and stop — server must not block the accept loop.
            SocketChannel.open(StandardProtocolFamily.UNIX).use { raw ->
                raw.connect(UnixDomainSocketAddress.of(udsPath))
                val output = Channels.newOutputStream(raw)
                output.write(byteArrayOf(0x00, 0x00)) // 2 of 4 length bytes
                output.flush()
                // Hold the socket open past the server's frame deadline.
                Thread.sleep(frameTimeoutMs + 200)
            }

            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3)) {
                IpcClient(udsPath, frameIoTimeoutMs = 5_000).use { client ->
                    assertEquals(
                        AgentResponse.Pong,
                        client.send(AgentRequest.Ping),
                        "accept loop must survive a mid-frame stall",
                    )
                }
            }
        }
    }

    @Test
    fun `client handshake times out when peer never responds`() {
        val frameTimeoutMs = 250L
        // Accept but never read/write — client Hello must time out, not hang.
        val serverChannel =
            java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX).also {
                Files.deleteIfExists(udsPath)
                it.bind(UnixDomainSocketAddress.of(udsPath))
            }
        val acceptThread =
            Thread(
                    {
                        try {
                            serverChannel.accept()?.use { /* hold open, no I/O */
                                Thread.sleep(5_000)
                            }
                        } catch (_: Exception) {
                            // closed
                        }
                    },
                    "stall-accept",
                )
                .apply {
                    isDaemon = true
                    start()
                }
        try {
            awaitSocket(udsPath)
            val started = System.nanoTime()
            val error =
                assertFailsWith<Exception> {
                    IpcClient(udsPath, frameIoTimeoutMs = frameTimeoutMs).use {}
                }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(
                FrameIoDeadline.isTimeout(error) ||
                    error is SocketTimeoutException ||
                    error.cause is SocketTimeoutException,
                "expected SocketTimeoutException (or wrapped); got $error",
            )
            assertTrue(
                elapsedMs < 2_000,
                "handshake should fail near frame timeout, not hang; elapsed=${elapsedMs}ms",
            )
        } finally {
            runCatching { serverChannel.close() }
            acceptThread.join(1_000)
        }
    }

    private fun stubHandler(): AgentRequestHandler = AgentRequestHandler { request ->
        when (request) {
            AgentRequest.Ping -> AgentResponse.Pong
            AgentRequest.Detach -> AgentResponse.Detached
            is AgentRequest.Hello ->
                AgentResponse.HelloAck(protocolVersion = ProtocolVersion.CURRENT)
            else -> AgentResponse.Error("unexpected $request")
        }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 2_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS file $path did not appear within ${timeoutMs} ms")
    }

    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
            Path.of(System.getProperty("java.io.tmpdir"))
        else Path.of("/tmp")
}
