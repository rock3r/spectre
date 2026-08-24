@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InputCoordinatorClientMixedReleaseFailureTest {

    @Test
    fun `persistence rejection followed by IO failure retains the exact release request`() {
        val directory = Files.createTempDirectory("spc-mr-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val codec = CoordinatorWireCodec()
        val listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        listener.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
        val backgroundReleaseCompleted = CountDownLatch(1)
        val sentinelClosed = CountDownLatch(1)
        val serverThread = startServer(listener, codec, sentinelClosed, backgroundReleaseCompleted)
        val client =
            LocalInputCoordinatorClient.connect(
                endpoint,
                DesktopResourceKey("test/mixed-release"),
                "mixed-release-test",
                codec,
            )
        try {
            val lease = client.acquire(Duration.ofSeconds(2), "test")

            val failure = assertFailsWith<InputCoordinatorException> { lease.close() }
            assertEquals("RECOVERY_PERSISTENCE_FAILED", failure.errorCode)
            assertFalse(
                sentinelClosed.await(200, TimeUnit.MILLISECONDS),
                "the retained release request must keep its session alive for retry",
            )

            client.close()

            assertTrue(
                backgroundReleaseCompleted.await(3, TimeUnit.SECONDS),
                "the exact release request was not retried after the mixed failure",
            )
            assertTrue(
                sentinelClosed.await(3, TimeUnit.SECONDS),
                "deferred close did not complete after release acknowledgement",
            )
        } finally {
            client.close()
            listener.close()
            serverThread.join(2_000)
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }

    private fun startServer(
        listener: ServerSocketChannel,
        codec: CoordinatorWireCodec,
        sentinelClosed: CountDownLatch,
        backgroundReleaseCompleted: CountDownLatch,
    ): Thread =
        Thread.ofVirtual().name("coordinator-mixed-release-failure-test").start {
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

            val requestId =
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
                    requireNotNull(acquire.requestId)
                }
            listener.accept().use { releaseChannel ->
                val release = codec.read(releaseChannel)
                assertEquals(CoordinatorWireKind.RELEASE, release.kind)
                assertEquals(requestId, release.requestId)
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
            listener.accept().use { retryChannel ->
                val retry = codec.read(retryChannel)
                assertEquals(CoordinatorWireKind.RELEASE, retry.kind)
                assertEquals(requestId, retry.requestId)
                // The retry never reaches the coordinator response path.
            }
            listener.accept().use { backgroundChannel ->
                val retry = codec.read(backgroundChannel)
                assertEquals(CoordinatorWireKind.RELEASE, retry.kind)
                assertEquals(requestId, retry.requestId)
                codec.write(
                    backgroundChannel,
                    CoordinatorWireMessage(kind = CoordinatorWireKind.RESPONSE),
                )
                backgroundReleaseCompleted.countDown()
            }
        }

    private companion object {
        const val EPOCH: String = "test-epoch"
        const val LEASE_ID: String = "test-lease"
    }
}
