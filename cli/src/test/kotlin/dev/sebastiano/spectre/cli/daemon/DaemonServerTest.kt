package dev.sebastiano.spectre.cli.daemon

import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DaemonServerTest {
    @Test
    fun `close unblocks an idle connected client`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        val server = DaemonServer(socketPath)
        val client = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        client.connect(java.net.UnixDomainSocketAddress.of(socketPath))

        try {
            server.close()
            assertTrue(server.awaitTermination(timeoutMillis = 2_000))
        } finally {
            client.close()
            server.close()
            Files.deleteIfExists(socketPath.parent)
        }
    }

    @Test
    fun `does not replace a regular file at the daemon socket path`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        Files.writeString(socketPath, "keep me")

        assertFailsWith<java.io.IOException> { DaemonServer(socketPath) }

        assertEquals("keep me", Files.readString(socketPath))
        Files.deleteIfExists(socketPath)
        Files.deleteIfExists(socketPath.parent)
    }

    @Test
    fun `zero termination timeout does not block`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        val server = DaemonServer(socketPath)

        try {
            assertFalse(server.awaitTermination(timeoutMillis = 0))
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            Files.deleteIfExists(socketPath.parent)
        }
    }

    @Test
    fun `replaces a stale daemon socket`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.bind(java.net.UnixDomainSocketAddress.of(socketPath))
        }
        assertTrue(Files.exists(socketPath))

        val server = DaemonServer(socketPath)

        try {
            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                DaemonWireCodec.writeRequest(output, DaemonRequest.ListSessions)
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    DaemonWireCodec.readResponse(input),
                )
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            Files.deleteIfExists(socketPath.parent)
        }
    }

    @Test
    fun `refuses to replace a live daemon socket`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        val firstServer = DaemonServer(socketPath)

        try {
            assertFailsWith<DaemonAlreadyRunningException> { DaemonServer(socketPath) }

            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocol.CurrentVersion),
                )
                assertEquals(
                    DaemonResponse.Hello(DaemonProtocol.CurrentVersion),
                    DaemonWireCodec.readResponse(input),
                )
            }
        } finally {
            firstServer.close()
            assertTrue(firstServer.awaitTermination())
            Files.deleteIfExists(socketPath.parent)
        }
    }

    @Test
    fun `creates an owner-only socket directory and socket file`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return

        // AF_UNIX paths are capped at about 104 bytes on macOS. Use /tmp rather than Gradle's
        // deeply nested test directory so the test exercises the intended permissions, not a
        // platform path-length limit.
        val tempDirectory = Path.of("/tmp", "sp-d-${UUID.randomUUID().toString().take(8)}")
        val socketParent = tempDirectory.resolve("private")
        val socketPath = socketParent.resolve("daemon.sock")
        val server = DaemonServer(socketPath)

        try {
            assertEquals(
                PosixFilePermissions.fromString("rwx------"),
                Files.getPosixFilePermissions(socketParent),
            )
            assertEquals(
                PosixFilePermissions.fromString("rw-------"),
                Files.getPosixFilePermissions(socketPath),
            )
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            Files.deleteIfExists(socketParent)
            Files.deleteIfExists(tempDirectory)
        }
    }

    @Test
    fun `serves lifecycle requests over a unix domain socket and removes it on shutdown`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        val server = DaemonServer(socketPath)

        try {
            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)

                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocol.CurrentVersion),
                )
                assertEquals(
                    DaemonResponse.Hello(DaemonProtocol.CurrentVersion),
                    DaemonWireCodec.readResponse(input),
                )

                DaemonWireCodec.writeRequest(output, DaemonRequest.Attach(1234))
                assertEquals(
                    DaemonResponse.Attached(sessionId = "pid-1234", targetPid = 1234),
                    DaemonWireCodec.readResponse(input),
                )

                DaemonWireCodec.writeRequest(output, DaemonRequest.Shutdown)
                assertEquals(DaemonResponse.ShuttingDown, DaemonWireCodec.readResponse(input))
            }

            assertTrue(server.awaitTermination())
            assertFalse(Files.exists(socketPath))
        } finally {
            server.close()
            Files.deleteIfExists(socketPath.parent)
        }
    }

    @Test
    fun `keeps accepting requests after a malformed client frame`() {
        val socketPath = Files.createTempDirectory("spectre-daemon-test").resolve("daemon.sock")
        val server = DaemonServer(socketPath)

        try {
            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                Channels.newOutputStream(channel).write(byteArrayOf(-1, -1, -1, -1))
            }

            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                DaemonWireCodec.writeRequest(output, DaemonRequest.ListSessions)

                val response =
                    assertIs<DaemonResponse.Sessions>(DaemonWireCodec.readResponse(input))
                assertTrue(response.sessions.isEmpty())
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            Files.deleteIfExists(socketPath.parent)
        }
    }
}
