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
        val socketPath = temporarySocketPath()
        val server = DaemonServer(socketPath)
        val client = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)
        client.connect(java.net.UnixDomainSocketAddress.of(socketPath))

        try {
            server.close()
            assertTrue(server.awaitTermination(timeoutMillis = 2_000))
        } finally {
            client.close()
            server.close()
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `does not replace a regular file at the daemon socket path`() {
        val socketPath = temporarySocketPath()
        val bootstrapServer = DaemonServer(socketPath.resolveSibling("bootstrap.sock"))
        Files.writeString(socketPath, "keep me")

        try {
            assertFailsWith<java.io.IOException> { DaemonServer(socketPath) }

            assertEquals("keep me", Files.readString(socketPath))
        } finally {
            Files.deleteIfExists(socketPath)
            bootstrapServer.close()
            assertTrue(bootstrapServer.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `zero termination timeout does not block`() {
        val socketPath = temporarySocketPath()
        val server = DaemonServer(socketPath)

        try {
            assertFalse(server.awaitTermination(timeoutMillis = 0))
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `replaces a stale daemon socket`() {
        val socketPath = temporarySocketPath()
        val bootstrapServer = DaemonServer(socketPath.resolveSibling("bootstrap.sock"))
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
                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocol.CurrentVersion),
                )
                assertEquals(
                    DaemonResponse.Hello(DaemonProtocol.CurrentVersion),
                    DaemonWireCodec.readResponse(input),
                )
                DaemonWireCodec.writeRequest(output, DaemonRequest.ListSessions)
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    DaemonWireCodec.readResponse(input),
                )
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            bootstrapServer.close()
            assertTrue(bootstrapServer.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `preserves an existing recovery lock file`() {
        val socketPath = temporarySocketPath()
        val bootstrapServer = DaemonServer(socketPath.resolveSibling("bootstrap.sock"))
        val lockPath = socketPath.resolveSibling("${socketPath.fileName}.lock")
        Files.writeString(lockPath, "preserve me")
        ServerSocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
            channel.bind(java.net.UnixDomainSocketAddress.of(socketPath))
        }

        val server = DaemonServer(socketPath)

        try {
            assertEquals("preserve me", Files.readString(lockPath))
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            Files.deleteIfExists(lockPath)
            bootstrapServer.close()
            assertTrue(bootstrapServer.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `refuses to replace a live daemon socket`() {
        val socketPath = temporarySocketPath()
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
            deleteTemporarySocketPath(socketPath)
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
    fun `refuses an existing socket directory with permissive permissions`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return

        val socketParent = Files.createTempDirectory("spectre-daemon-test")
        Files.setPosixFilePermissions(socketParent, PosixFilePermissions.fromString("rwxrwxrwx"))
        val socketPath = socketParent.resolve("daemon.sock")
        try {
            assertFailsWith<java.io.IOException> { DaemonServer(socketPath) }
            assertEquals(
                PosixFilePermissions.fromString("rwxrwxrwx"),
                Files.getPosixFilePermissions(socketParent),
            )
        } finally {
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(socketParent)
        }
    }

    @Test
    fun `refuses an existing socket directory below a writable ancestor`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return

        val ancestor = Files.createTempDirectory("spectre-daemon-test")
        val socketDirectory = Files.createDirectory(ancestor.resolve("socket-directory"))
        Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwxrwxrwx"))
        Files.setPosixFilePermissions(socketDirectory, PosixFilePermissions.fromString("rwx------"))

        try {
            assertFailsWith<java.io.IOException> {
                DaemonServer(socketDirectory.resolve("daemon.sock"))
            }
        } finally {
            Files.deleteIfExists(socketDirectory.resolve("daemon.sock"))
            Files.deleteIfExists(socketDirectory)
            Files.deleteIfExists(ancestor)
        }
    }

    @Test
    fun `refuses a bare relative socket path in a permissive working directory`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return
        if (
            Files.getPosixFilePermissions(Path.of("")) ==
                PosixFilePermissions.fromString("rwx------")
        ) {
            return
        }

        assertFailsWith<java.io.IOException> { DaemonServer(Path.of("daemon.sock")) }
    }

    @Test
    fun `requires a compatible hello before session commands`() {
        val socketPath = temporarySocketPath()
        val server = DaemonServer(socketPath)

        try {
            SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)

                DaemonWireCodec.writeRequest(output, DaemonRequest.Attach(1234))
                assertEquals(
                    DaemonResponse.Error(
                        DaemonErrorCode.ProtocolError,
                        "send a compatible Hello request before session commands",
                    ),
                    DaemonWireCodec.readResponse(input),
                )

                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocolVersion(major = 2, minor = 0)),
                )
                assertEquals(
                    DaemonResponse.Error(
                        DaemonErrorCode.ProtocolError,
                        "incompatible daemon protocol version",
                    ),
                    DaemonWireCodec.readResponse(input),
                )

                DaemonWireCodec.writeRequest(output, DaemonRequest.Attach(1234))
                assertEquals(
                    DaemonResponse.Error(
                        DaemonErrorCode.ProtocolError,
                        "send a compatible Hello request before session commands",
                    ),
                    DaemonWireCodec.readResponse(input),
                )
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `preserves a dangling symlink in the socket path`() {
        val temporaryDirectory = Files.createTempDirectory("spectre-daemon-test")
        val danglingLink = temporaryDirectory.resolve("link")
        Files.createSymbolicLink(danglingLink, temporaryDirectory.resolve("missing-target"))

        try {
            assertFailsWith<java.io.IOException> {
                DaemonServer(danglingLink.resolve("daemon.sock"))
            }
            assertTrue(Files.isSymbolicLink(danglingLink))
        } finally {
            Files.deleteIfExists(danglingLink)
            Files.deleteIfExists(temporaryDirectory)
        }
    }

    @Test
    fun `refuses a socket parent that is a symbolic link`() {
        val temporaryDirectory = Files.createTempDirectory("spectre-daemon-test")
        val socketDirectory = Files.createDirectory(temporaryDirectory.resolve("socket-directory"))
        val socketLink = temporaryDirectory.resolve("socket-link")
        Files.createSymbolicLink(socketLink, socketDirectory)

        try {
            assertFailsWith<java.io.IOException> { DaemonServer(socketLink.resolve("daemon.sock")) }
            assertTrue(Files.isSymbolicLink(socketLink))
        } finally {
            Files.deleteIfExists(socketLink)
            Files.deleteIfExists(socketDirectory)
            Files.deleteIfExists(temporaryDirectory)
        }
    }

    @Test
    fun `serves lifecycle requests over a unix domain socket and removes it on shutdown`() {
        val socketPath = temporarySocketPath()
        val server = DaemonServer(socketPath, registry = DaemonSessionRegistry { AutoCloseable {} })

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
            assertFalse(Files.exists(socketPath.parent))
            if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
                assertFalse(Files.exists(socketPath.parent.parent))
            }
        } finally {
            server.close()
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `keeps accepting requests after a malformed client frame`() {
        val socketPath = temporarySocketPath()
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
                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocol.CurrentVersion),
                )
                assertEquals(
                    DaemonResponse.Hello(DaemonProtocol.CurrentVersion),
                    DaemonWireCodec.readResponse(input),
                )
                DaemonWireCodec.writeRequest(output, DaemonRequest.ListSessions)

                val response =
                    assertIs<DaemonResponse.Sessions>(DaemonWireCodec.readResponse(input))
                assertTrue(response.sessions.isEmpty())
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }
}

private fun temporarySocketPath(): Path =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Path.of("/tmp", "sp-d-${UUID.randomUUID().toString().take(8)}", "daemon", "daemon.sock")
    } else {
        Files.createTempDirectory("spectre-daemon-test").resolve("daemon").resolve("daemon.sock")
    }

private fun deleteTemporarySocketPath(socketPath: Path) {
    Files.deleteIfExists(socketPath)
    Files.deleteIfExists(socketPath.parent)
    Files.deleteIfExists(socketPath.parent.parent)
}
