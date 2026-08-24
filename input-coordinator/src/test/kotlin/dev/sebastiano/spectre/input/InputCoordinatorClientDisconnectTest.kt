@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.io.InterruptedIOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InputCoordinatorClientDisconnectTest {

    @Test
    fun `maximum acquisition timeout does not overflow the response deadline`() {
        val directory = Files.createTempDirectory("spc-cl-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val receivedTimeout = CompletableFuture<Long>()
        val serverThread =
            Thread.ofVirtual().name("coordinator-long-acquire-test").start {
                val session = listener.accept()
                assertEquals(CoordinatorWireKind.SESSION_OPEN, codec.read(session).kind)
                codec.write(
                    session,
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.RESPONSE,
                        coordinatorEpoch = EPOCH,
                    ),
                )

                listener.accept().use { acquireChannel ->
                    val acquire = codec.read(acquireChannel)
                    receivedTimeout.complete(requireNotNull(acquire.timeoutMillis))
                    codec.write(
                        acquireChannel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            requestId = acquire.requestId,
                            coordinatorEpoch = EPOCH,
                            leaseId = LEASE_ID,
                            resourceKey = acquire.resourceKey,
                            fence = 1,
                        ),
                    )
                }
                listener.accept().use { releaseChannel ->
                    val release = codec.read(releaseChannel)
                    assertEquals(CoordinatorWireKind.RELEASE, release.kind)
                    codec.write(
                        releaseChannel,
                        CoordinatorWireMessage(kind = CoordinatorWireKind.RESPONSE),
                    )
                }
                session.close()
            }
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/long-acquire"),
                "long-acquire-test",
                codec,
            )
        try {
            client.acquire(Duration.ofMillis(Long.MAX_VALUE), "long acquire").close()

            assertEquals(Long.MAX_VALUE, receivedTimeout.get(2, TimeUnit.SECONDS))
        } finally {
            client.close()
            listener.close()
            serverThread.join(2_000)
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `heartbeat IO failure closes the sentinel session`() {
        val directory = Files.createTempDirectory("spc-cd-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val sentinelClosed = CountDownLatch(1)
        val serverThread =
            Thread.ofVirtual().name("coordinator-disconnect-test").start {
                val session = listener.accept()
                val sessionOpen = codec.read(session)
                assertEquals(CoordinatorWireKind.SESSION_OPEN, sessionOpen.kind)
                codec.write(
                    session,
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.RESPONSE,
                        coordinatorEpoch = EPOCH,
                    ),
                )
                Thread.ofVirtual().start {
                    codec.readOrNull(session)
                    sentinelClosed.countDown()
                }

                listener.accept().use { acquireChannel ->
                    val acquire = codec.read(acquireChannel)
                    codec.write(
                        acquireChannel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            requestId = acquire.requestId,
                            coordinatorEpoch = EPOCH,
                            leaseId = LEASE_ID,
                            resourceKey = acquire.resourceKey,
                            fence = 1,
                        ),
                    )
                }
                listener.accept().use { heartbeatChannel ->
                    codec.read(heartbeatChannel)
                    // Closing without a response simulates a failed short-lived heartbeat while
                    // the long-lived sentinel session is still healthy.
                }
            }
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/disconnect"),
                "disconnect-test",
                codec,
            )
        try {
            val lease = client.acquire(Duration.ofSeconds(2), "test")

            assertFailsWith<IOException> { lease.checkpoint() }
            assertTrue(
                sentinelClosed.await(2, TimeUnit.SECONDS),
                "heartbeat I/O failure should close the sentinel session immediately",
            )
        } finally {
            client.close()
            listener.close()
            serverThread.join(2_000)
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `ambiguous acquisition IO failure closes the sentinel session`() {
        val directory = Files.createTempDirectory("spc-ca-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val sentinelClosed = CountDownLatch(1)
        val serverThread =
            Thread.ofVirtual().name("coordinator-ambiguous-acquire-test").start {
                val session = listener.accept()
                val sessionOpen = codec.read(session)
                assertEquals(CoordinatorWireKind.SESSION_OPEN, sessionOpen.kind)
                codec.write(
                    session,
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.RESPONSE,
                        coordinatorEpoch = EPOCH,
                    ),
                )
                Thread.ofVirtual().start {
                    codec.readOrNull(session)
                    sentinelClosed.countDown()
                }

                listener.accept().use { acquireChannel ->
                    assertEquals(CoordinatorWireKind.ACQUIRE, codec.read(acquireChannel).kind)
                    // The server-side state granted the request, but the response was lost.
                }
                listener.accept().use { cancelChannel ->
                    assertEquals(CoordinatorWireKind.CANCEL, codec.read(cancelChannel).kind)
                    codec.write(cancelChannel, CoordinatorWireMessage(CoordinatorWireKind.RESPONSE))
                }
            }
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/ambiguous-acquire"),
                "ambiguous-acquire-test",
                codec,
            )
        try {
            assertFailsWith<IOException> {
                client.acquire(Duration.ofSeconds(2), "ambiguous acquire")
            }
            assertTrue(
                sentinelClosed.await(2, TimeUnit.SECONDS),
                "ambiguous acquisition should close the session so an unknown grant is released",
            )
        } finally {
            client.close()
            listener.close()
            serverThread.join(2_000)
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `unacknowledged cancellation after interrupted acquisition closes the sentinel session`() {
        val directory = Files.createTempDirectory("spc-ci-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val acquireReceived = CountDownLatch(1)
        val sentinelClosed = CountDownLatch(1)
        val serverThread =
            Thread.ofVirtual().name("coordinator-interrupted-cancel-test").start {
                val session = listener.accept()
                assertEquals(CoordinatorWireKind.SESSION_OPEN, codec.read(session).kind)
                codec.write(
                    session,
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.RESPONSE,
                        coordinatorEpoch = EPOCH,
                    ),
                )
                Thread.ofVirtual().start {
                    codec.readOrNull(session)
                    sentinelClosed.countDown()
                }

                listener.accept().use { acquireChannel ->
                    assertEquals(CoordinatorWireKind.ACQUIRE, codec.read(acquireChannel).kind)
                    acquireReceived.countDown()
                    codec.readOrNull(acquireChannel)
                }
                listener.accept().use { cancelChannel ->
                    assertEquals(CoordinatorWireKind.CANCEL, codec.read(cancelChannel).kind)
                    // Closing without a response leaves cancellation unacknowledged.
                }
            }
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/interrupted-cancel"),
                "interrupted-cancel-test",
                codec,
            )
        try {
            val failure = CompletableFuture<Throwable>()
            val acquireThread =
                Thread.ofVirtual().start {
                    failure.complete(
                        runCatching { client.acquire(Duration.ofSeconds(2), "interrupted acquire") }
                            .exceptionOrNull()
                            ?: AssertionError("Interrupted acquisition unexpectedly succeeded")
                    )
                }
            assertTrue(acquireReceived.await(2, TimeUnit.SECONDS))
            acquireThread.interrupt()

            assertTrue(failure.get(2, TimeUnit.SECONDS) is InterruptedIOException)
            assertTrue(
                sentinelClosed.await(2, TimeUnit.SECONDS),
                "unacknowledged cancellation must fence the session and release an unknown grant",
            )
        } finally {
            client.close()
            listener.close()
            serverThread.join(2_000)
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }

    @Test
    fun `release error closes the sentinel session`() {
        val directory = Files.createTempDirectory("spc-cr-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val sentinelClosed = CountDownLatch(1)
        var acquireRequestId: String? = null
        val serverThread =
            Thread.ofVirtual().name("coordinator-release-error-test").start {
                val session = listener.accept()
                assertEquals(CoordinatorWireKind.SESSION_OPEN, codec.read(session).kind)
                codec.write(
                    session,
                    CoordinatorWireMessage(
                        kind = CoordinatorWireKind.RESPONSE,
                        coordinatorEpoch = EPOCH,
                    ),
                )
                Thread.ofVirtual().start {
                    codec.readOrNull(session)
                    sentinelClosed.countDown()
                }

                listener.accept().use { acquireChannel ->
                    val acquire = codec.read(acquireChannel)
                    acquireRequestId = acquire.requestId
                    codec.write(
                        acquireChannel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            requestId = acquire.requestId,
                            coordinatorEpoch = EPOCH,
                            leaseId = LEASE_ID,
                            resourceKey = acquire.resourceKey,
                            fence = 1,
                        ),
                    )
                }
                listener.accept().use { releaseChannel ->
                    val release = codec.read(releaseChannel)
                    assertEquals(CoordinatorWireKind.RELEASE, release.kind)
                    assertEquals(acquireRequestId, release.requestId)
                    codec.write(
                        releaseChannel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            ok = false,
                            errorCode = "RECOVERY_PERSISTENCE_FAILED",
                            message = "Could not persist lease release",
                        ),
                    )
                }
            }
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/release-error"),
                "release-error-test",
                codec,
            )
        try {
            client.acquire(Duration.ofSeconds(2), "test").close()

            assertTrue(
                sentinelClosed.await(2, TimeUnit.SECONDS),
                "release failure should close the session so the retained grant is released",
            )
        } finally {
            client.close()
            listener.close()
            serverThread.join(2_000)
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }

    private companion object {
        const val EPOCH: String = "test-epoch"
        const val LEASE_ID: String = "test-lease"
    }
}
