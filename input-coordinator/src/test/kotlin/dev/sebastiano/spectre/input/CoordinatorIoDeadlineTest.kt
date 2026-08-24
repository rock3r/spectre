@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.Executors
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CoordinatorIoDeadlineTest {

    @Test
    fun `bounded request fails when an accepted coordinator never responds`() {
        val directory = Files.createTempDirectory("spc-io-")
        val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
        val server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        val accepter = Executors.newSingleThreadExecutor()
        try {
            server.bind(UnixDomainSocketAddress.of(endpoint.socketPath))
            val accepted = accepter.submit { server.accept().use { Thread.sleep(5_000) } }

            assertFailsWith<java.net.SocketTimeoutException> {
                sendCoordinatorMessage(
                    endpoint = endpoint,
                    codec = CoordinatorWireCodec(),
                    message = CoordinatorWireMessage(kind = CoordinatorWireKind.HEALTH),
                    timeout = Duration.ofMillis(50),
                )
            }

            accepted.cancel(true)
        } finally {
            accepter.shutdownNow()
            server.close()
            Files.deleteIfExists(endpoint.socketPath)
            Files.deleteIfExists(directory)
        }
    }
}
