@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.EOFException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalInputCoordinatorControlStatusTest {
    @Test
    fun `status propagates EOF after connecting to a coordinator`() {
        withServer { endpoint, server ->
            val accepter =
                Thread.ofVirtual().start { server.accept().use { CoordinatorWireCodec().read(it) } }

            assertFailsWith<EOFException> {
                LocalInputCoordinatorControl(endpoint).status(DesktopResourceKey("test/status-eof"))
            }
            accepter.join()
        }
    }

    @Test
    fun `status propagates timeout after connecting to an unresponsive coordinator`() {
        withServer { endpoint, server ->
            val requestRead = CountDownLatch(1)
            val release = CountDownLatch(1)
            val accepter =
                Thread.ofVirtual().start {
                    server.accept().use {
                        CoordinatorWireCodec().read(it)
                        requestRead.countDown()
                        release.await()
                    }
                }
            try {
                assertFailsWith<SocketTimeoutException> {
                    LocalInputCoordinatorControl(endpoint)
                        .status(DesktopResourceKey("test/status-timeout"))
                }
                assertTrue(requestRead.await(1, TimeUnit.SECONDS))
            } finally {
                release.countDown()
                accepter.join()
            }
        }
    }

    @Test
    fun `status preserves caller interruption instead of reporting absence`() {
        withServer { endpoint, server ->
            val accepted = CountDownLatch(1)
            val release = CountDownLatch(1)
            val accepter =
                Thread.ofVirtual().start {
                    server.accept().use {
                        accepted.countDown()
                        release.await()
                    }
                }
            val failure = CompletableFuture<Throwable>()
            val caller =
                Thread.ofVirtual().start {
                    failure.complete(
                        runCatching {
                            LocalInputCoordinatorControl(endpoint)
                                .status(DesktopResourceKey("test/status-interrupted"))
                        }
                            .exceptionOrNull()
                            ?: AssertionError("Interrupted status unexpectedly succeeded")
                    )
                }
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            caller.interrupt()

            assertTrue(failure.get(2, TimeUnit.SECONDS) is InterruptedIOException)
            assertTrue(caller.isInterrupted)
            release.countDown()
            accepter.join()
        }
    }

    private fun withServer(block: (CoordinatorEndpoint, ServerSocketChannel) -> Unit) {
        val directory = Files.createTempDirectory("spc-status-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            try {
                server.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
                block(endpoint, server)
            } finally {
                Files.deleteIfExists(endpoint.socketPath)
                Files.deleteIfExists(directory)
            }
        }
    }
}
