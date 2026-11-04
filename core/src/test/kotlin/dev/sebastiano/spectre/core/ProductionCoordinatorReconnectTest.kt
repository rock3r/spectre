@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.CoordinatorEndpoint
import dev.sebastiano.spectre.input.CoordinatorWireCodec
import dev.sebastiano.spectre.input.CoordinatorWireKind
import dev.sebastiano.spectre.input.CoordinatorWireMessage
import dev.sebastiano.spectre.input.DesktopResourceKey
import dev.sebastiano.spectre.input.InputCoordinatorException
import dev.sebastiano.spectre.input.LocalInputCoordinatorClient
import dev.sebastiano.spectre.input.server.LocalCoordinatorServer
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.time.Duration as JavaDuration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking

class ProductionCoordinatorReconnectTest {

    // A coordinator restart races with the old session's watcher thread: until that thread
    // sees EOF, the cached client still looks connected and sends ACQUIRE to the new server,
    // which does not know the client and answers CLIENT_DISCONNECTED (#593). This test makes
    // that window permanent. A minimal stand-in coordinator opens the session and keeps its
    // socket open, then a real server takes over the socket path. The cached client never
    // sees a disconnect, so its next ACQUIRE always reaches a server that does not know it.
    @Test
    fun `production coordinator reconnects when the server does not know its session`() =
        runBlocking {
            val directory = Files.createTempDirectory("spc-u-")
            val endpoint = CoordinatorEndpoint(directory, directory.resolve("coordinator.sock"))
            val resource = DesktopResourceKey("test/unknown-session")
            val previousCoordinator = SessionOnlyCoordinator(endpoint)
            var server: LocalCoordinatorServer? = null
            val coordinator =
                ProductionInputLeaseCoordinator(
                    connectClient = { label ->
                        LocalInputCoordinatorClient.connect(endpoint, resource, label)
                    }
                )
            try {
                val contended =
                    assertFailsWith<InputCoordinatorException> {
                        coordinator.acquire(InputLeaseOptions(), "cache session", immediate = false)
                    }
                assertEquals("LEASE_CONTENDED", contended.errorCode)
                previousCoordinator.stopListening()
                Files.deleteIfExists(endpoint.socketPath)
                server =
                    LocalCoordinatorServer(endpoint, idleTimeout = JavaDuration.ofMinutes(1)).also {
                        it.start()
                    }

                coordinator
                    .acquire(InputLeaseOptions(), "after takeover", immediate = false)
                    .close()
            } finally {
                coordinator.close()
                previousCoordinator.close()
                server?.close()
                Files.deleteIfExists(endpoint.socketPath)
                Files.deleteIfExists(directory.resolve("coordinator.lock"))
                Files.deleteIfExists(directory.resolve("recovery.properties.tmp"))
                Files.deleteIfExists(directory.resolve("recovery.properties"))
                Files.deleteIfExists(directory)
            }
        }
}

/**
 * Stand-in for a coordinator that has just stopped: it accepts owner sessions and keeps their
 * sockets open, and it rejects every other request as contended. After [stopListening], the
 * sessions it opened still look connected to their clients.
 */
private class SessionOnlyCoordinator(endpoint: CoordinatorEndpoint) : AutoCloseable {
    private val codec = CoordinatorWireCodec()
    private val listener =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            .bind(UnixDomainSocketAddress.of(endpoint.socketPath))
    private val sessions = CopyOnWriteArrayList<SocketChannel>()
    private val acceptThread = Thread.ofVirtual().name("spectre-test-session-only").start(::serve)

    private fun serve() {
        while (true) {
            val channel = runCatching { listener.accept() }.getOrNull() ?: return
            runCatching {
                val request = codec.read(channel)
                if (request.kind == CoordinatorWireKind.SESSION_OPEN) {
                    codec.write(
                        channel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            coordinatorEpoch = "previous-epoch",
                        ),
                    )
                    sessions += channel
                } else {
                    codec.write(
                        channel,
                        CoordinatorWireMessage(
                            kind = CoordinatorWireKind.RESPONSE,
                            ok = false,
                            errorCode = "LEASE_CONTENDED",
                            message = "Desktop input lease is currently held",
                        ),
                    )
                    channel.close()
                }
            }
        }
    }

    fun stopListening() {
        listener.close()
        acceptThread.join()
    }

    override fun close() {
        stopListening()
        sessions.forEach { runCatching { it.close() } }
    }
}
