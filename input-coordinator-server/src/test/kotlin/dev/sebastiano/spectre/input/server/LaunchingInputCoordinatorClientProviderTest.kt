@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.CoordinatorWireCodec
import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.InputCoordinatorException
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

class LaunchingInputCoordinatorClientProviderTest {
    @Test
    fun `busy coordinator closed before reading session open is never relaunched`() {
        val directory = compactTemporaryDirectory("spc-pb-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val responseWritten = CountDownLatch(1)
        val launchCount = AtomicInteger()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { listener ->
            listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
            val server =
                Thread.ofVirtual().start {
                    listener.accept().use { channel ->
                        codec.write(
                            channel,
                            CoordinatorWireMessage(
                                kind = CoordinatorWireKind.RESPONSE,
                                ok = false,
                                errorCode = "CONNECTION_LIMIT_EXCEEDED",
                                message = "busy",
                            ),
                        )
                        responseWritten.countDown()
                        // Deliberately do not read SESSION_OPEN. Closing here can make the client's
                        // write fail even though the complete busy response is already readable.
                    }
                }
            try {
                val failure =
                    runCatching {
                        LaunchingInputCoordinatorClientProvider { launchCount.incrementAndGet() }
                            .connect(endpoint, DesktopResourceKey("test/busy"), "test")
                    }
                        .exceptionOrNull() ?: fail("preclosed coordinator unexpectedly connected")

                when (failure) {
                    is InputCoordinatorException ->
                        assertEquals("CONNECTION_LIMIT_EXCEEDED", failure.errorCode)
                    is SocketException -> Unit // Windows may reset a peer with unread request data.
                    else -> fail("unexpected failure from preclosed coordinator", failure)
                }
                assertTrue(responseWritten.await(1, TimeUnit.SECONDS))
                assertEquals(0, launchCount.get())
                server.join()
            } finally {
                Files.deleteIfExists(endpoint.socketPath)
                Files.deleteIfExists(directory)
            }
        }
    }

    @Test
    fun `real busy coordinator returns structured error without relaunching`() {
        val directory = compactTemporaryDirectory("spc-pb-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val accepted = CountDownLatch(1)
        val launchCount = AtomicInteger()
        val server =
            LocalCoordinatorServer(
                endpoint = endpoint,
                idleTimeout = Duration.ofMinutes(1),
                activeConnectionLimit = 1,
                connectionAccepted = accepted::countDown,
            )
        server.start()
        val stalled = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            stalled.connect(UnixDomainSocketAddress.of(endpoint.socketPath))
            assertTrue(accepted.await(1, TimeUnit.SECONDS))
            assertEquals(1, server.activeConnectionCount)

            val failure =
                assertFailsWith<InputCoordinatorException> {
                    LaunchingInputCoordinatorClientProvider { launchCount.incrementAndGet() }
                        .connect(endpoint, DesktopResourceKey("test/busy"), "test")
                }

            assertEquals("CONNECTION_LIMIT_EXCEEDED", failure.errorCode)
            assertEquals(0, launchCount.get())
            assertEquals(1, server.activeConnectionCount)
        } finally {
            stalled.close()
            server.close()
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory.resolve("coordinator.lock"))
            Files.deleteIfExists(directory.resolve("recovery.properties.tmp"))
            Files.deleteIfExists(directory.resolve("recovery.properties"))
            Files.deleteIfExists(directory)
        }
    }

    private fun compactTemporaryDirectory(prefix: String): Path {
        val compactRoot = Path.of("/tmp")
        return if (
            !System.getProperty("os.name").startsWith("Windows", ignoreCase = true) &&
                Files.isDirectory(compactRoot)
        ) {
            Files.createTempDirectory(compactRoot, prefix)
        } else {
            Files.createTempDirectory(prefix)
        }
    }
}
