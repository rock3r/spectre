@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InputCoordinatorClientDisconnectTest {

    @Test
    fun `heartbeat IO failure closes the sentinel session`() {
        val directory = Files.createTempDirectory(Path.of("/tmp"), "spc-cd-")
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

    private companion object {
        const val EPOCH: String = "test-epoch"
        const val LEASE_ID: String = "test-lease"
    }
}
