@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

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

class InputCoordinatorClientAcquireValidationTest {

    @Test
    fun `malformed granted response cancels the exact acquisition and fences the session`() {
        val directory = Files.createTempDirectory("spc-cv-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val acquireRequestId = CompletableFuture<String>()
        val cancellation = CompletableFuture<CoordinatorWireMessage>()
        val sentinelClosed = CountDownLatch(1)
        val serverThread =
            Thread.ofVirtual().name("coordinator-malformed-grant-test").start {
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
                    acquireRequestId.complete(requireNotNull(acquire.requestId))
                    codec.write(
                        acquireChannel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            requestId = acquire.requestId,
                            coordinatorEpoch = EPOCH,
                            leaseId = LEASE_ID,
                            resourceKey = acquire.resourceKey,
                            // A committed grant without a fence is not a valid grant response.
                            fence = null,
                        ),
                    )
                }
                runCatching {
                        listener.accept().use { cancelChannel ->
                            val cancel = codec.read(cancelChannel)
                            cancellation.complete(cancel)
                            codec.write(
                                cancelChannel,
                                CoordinatorWireMessage(kind = CoordinatorWireKind.RESPONSE),
                            )
                        }
                    }
                    .onFailure(cancellation::completeExceptionally)
            }
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/malformed-grant"),
                "malformed-grant-test",
                codec,
            )
        try {
            assertFailsWith<IllegalArgumentException> {
                client.acquire(Duration.ofSeconds(2), "malformed grant")
            }

            val cancel = cancellation.get(2, TimeUnit.SECONDS)
            assertEquals(CoordinatorWireKind.CANCEL, cancel.kind)
            assertEquals(acquireRequestId.get(2, TimeUnit.SECONDS), cancel.requestId)
            assertTrue(
                sentinelClosed.await(2, TimeUnit.SECONDS),
                "an invalid grant response must fence the owner session",
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
        const val EPOCH: String = "epoch"
        const val LEASE_ID: String = "lease"
    }
}
