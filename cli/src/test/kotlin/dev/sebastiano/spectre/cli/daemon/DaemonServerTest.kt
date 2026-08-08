package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
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
import org.junit.jupiter.api.Assumptions.assumeTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonServerTest {
    @Test
    fun `idle cleanup keeps an accepted client alive through its first request`() {
        val socketPath = temporarySocketPath()
        val server = DaemonServer(socketPath)
        val client = SocketChannel.open(java.net.StandardProtocolFamily.UNIX)

        try {
            client.connect(java.net.UnixDomainSocketAddress.of(socketPath))
            Thread.sleep(100)

            assertFalse(server.closeIfIdle(timeoutMillis = 1))
            assertTrue(Files.exists(socketPath))
        } finally {
            client.close()
            server.close()
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `idle cleanup keeps servers with active sessions alive`() {
        val socketPath = temporarySocketPath()
        val registry = DaemonSessionRegistry { TestDaemonSessionAutomator() }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            registry.handle(DaemonRequest.Attach(1234))
            Thread.sleep(10)

            assertFalse(server.closeIfIdle(timeoutMillis = 1))
            assertTrue(Files.exists(socketPath))
        } finally {
            server.close()
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `close detaches all owned agent sessions`() {
        val socketPath = temporarySocketPath()
        var closes = 0
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(closeAction = { closes++ })
        }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            registry.handle(DaemonRequest.Attach(1234))

            server.close()

            assertEquals(1, closes)
        } finally {
            server.close()
            deleteTemporarySocketPath(socketPath)
        }
    }

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
        assumeCanCreateSymbolicLinks()
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
        assumeCanCreateSymbolicLinks()
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
        val server =
            DaemonServer(
                socketPath,
                registry = DaemonSessionRegistry { TestDaemonSessionAutomator() },
            )

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

    // region #413 disconnect ≠ detach + concurrent multi-client detach

    @Test
    fun `client disconnect does not detach registry sessions`() {
        val socketPath = temporarySocketPath()
        val registry = DaemonSessionRegistry { TestDaemonSessionAutomator() }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            // One-shot attach (DaemonClient closes the connection after the response).
            val attached =
                assertIs<DaemonResponse.Attached>(
                    DaemonClient(socketPath).request(DaemonRequest.Attach(4242))
                )
            assertEquals("pid-4242", attached.sessionId)

            // New client after front-end death still sees the session and can operate it.
            val listed =
                assertIs<DaemonResponse.Sessions>(
                    DaemonClient(socketPath).request(DaemonRequest.ListSessions)
                )
            assertEquals(
                listOf(DaemonSessionSummary(sessionId = "pid-4242", targetPid = 4242)),
                listed.sessions,
            )
            assertIs<DaemonResponse.Windows>(
                DaemonClient(socketPath).request(DaemonRequest.Windows("pid-4242"))
            )

            // Explicit detach remains the recovery path.
            assertIs<DaemonResponse.Detached>(
                DaemonClient(socketPath).request(DaemonRequest.Detach("pid-4242"))
            )
            val after =
                assertIs<DaemonResponse.Sessions>(
                    DaemonClient(socketPath).request(DaemonRequest.ListSessions)
                )
            assertTrue(after.sessions.isEmpty())
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `mid-request client disconnect does not detach the target session`() {
        val socketPath = temporarySocketPath()
        val waitEntered = java.util.concurrent.CountDownLatch(1)
        val holdWait = java.util.concurrent.CountDownLatch(1)
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                waitForNodeResult = { _, _, _, _ ->
                    waitEntered.countDown()
                    // Hold until the test finishes assertions, then fail closed.
                    holdWait.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    throw java.io.IOException("client abandoned wait")
                }
            )
        }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(777))
            )

            val waitThread =
                Thread(
                    {
                        SocketChannel.open(java.net.StandardProtocolFamily.UNIX).use { channel ->
                            channel.connect(java.net.UnixDomainSocketAddress.of(socketPath))
                            val input = Channels.newInputStream(channel)
                            val output = Channels.newOutputStream(channel)
                            DaemonWireCodec.writeRequest(
                                output,
                                DaemonRequest.Hello(DaemonProtocol.CurrentVersion),
                            )
                            DaemonWireCodec.readResponse(input)
                            DaemonWireCodec.writeRequest(
                                output,
                                DaemonRequest.WaitForNode(
                                    sessionId = "pid-777",
                                    tag = "never",
                                    timeoutMs = 60_000,
                                ),
                            )
                            // Drop the front-end before reading a response (#413).
                            check(waitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            // Channel closes on use{} exit while the daemon wait is still held.
                        }
                    },
                    "spectre-test-mid-disconnect",
                )
            waitThread.start()
            check(waitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            waitThread.join(5_000)

            // While the abandoned wait is still in flight, the session must remain operable.
            val listed =
                assertIs<DaemonResponse.Sessions>(
                    DaemonClient(socketPath).request(DaemonRequest.ListSessions)
                )
            assertEquals(
                listOf(DaemonSessionSummary(sessionId = "pid-777", targetPid = 777)),
                listed.sessions,
                "front-end disconnect must not silently detach the daemon session",
            )
            assertIs<DaemonResponse.Windows>(
                DaemonClient(socketPath).request(DaemonRequest.Windows("pid-777"))
            )

            holdWait.countDown()
            assertIs<DaemonResponse.Detached>(
                DaemonClient(socketPath).request(DaemonRequest.Detach("pid-777"))
            )
        } finally {
            holdWait.countDown()
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    @Test
    fun `concurrent wait and detach from separate clients fail closed without hang`() {
        val socketPath = temporarySocketPath()
        val waitEntered = java.util.concurrent.CountDownLatch(1)
        val closedDuringWait = java.util.concurrent.CountDownLatch(1)
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                waitForNodeResult = { _, _, _, _ ->
                    waitEntered.countDown()
                    check(closedDuringWait.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                        "detach did not close automator while wait was in flight"
                    }
                    throw java.io.IOException("session closed during waitForNode")
                },
                closeAction = { closedDuringWait.countDown() },
            )
        }
        val server = DaemonServer(socketPath, registry = registry)

        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(888))
            )

            val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
            try {
                val waitFuture =
                    pool.submit<DaemonResponse> {
                        DaemonClient(socketPath)
                            .request(
                                DaemonRequest.WaitForNode(
                                    sessionId = "pid-888",
                                    tag = "never",
                                    timeoutMs = 60_000,
                                )
                            )
                    }
                check(waitEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))

                val detachStarted = System.nanoTime()
                val detached =
                    assertIs<DaemonResponse.Detached>(
                        DaemonClient(socketPath).request(DaemonRequest.Detach("pid-888"))
                    )
                assertEquals("pid-888", detached.sessionId)
                val detachMs =
                    java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                        System.nanoTime() - detachStarted
                    )
                assertTrue(
                    detachMs < 10_000,
                    "multi-client detach must not hang on the full wait budget (took ${detachMs}ms)",
                )

                val waitError =
                    assertIs<DaemonResponse.Error>(
                        waitFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    )
                assertEquals(DaemonErrorCode.OperationFailed, waitError.code)

                val listed =
                    assertIs<DaemonResponse.Sessions>(
                        DaemonClient(socketPath).request(DaemonRequest.ListSessions)
                    )
                assertTrue(listed.sessions.isEmpty())

                assertIs<DaemonResponse.Attached>(
                    DaemonClient(socketPath).request(DaemonRequest.Attach(999))
                )
            } finally {
                pool.shutdownNow()
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporarySocketPath(socketPath)
        }
    }

    // endregion
}

/**
 * Windows requires Developer Mode or SeCreateSymbolicLinkPrivilege for [Files.createSymbolicLink].
 * Without it the probe throws [java.nio.file.FileSystemException] and symlink-guard unit tests must
 * assumption-skip rather than fail closed the whole `check` gate.
 */
private fun assumeCanCreateSymbolicLinks() {
    val probeDir = Files.createTempDirectory("spectre-symlink-probe")
    var canCreate = false
    try {
        val link = probeDir.resolve("link")
        Files.createSymbolicLink(link, probeDir.resolve("target-does-not-need-to-exist"))
        Files.deleteIfExists(link)
        canCreate = true
    } catch (_: java.nio.file.FileSystemException) {
        canCreate = false
    } catch (_: UnsupportedOperationException) {
        canCreate = false
    } finally {
        Files.deleteIfExists(probeDir)
    }
    assumeTrue(
        canCreate,
        "Host cannot create symbolic links (Windows: enable Developer Mode or grant " +
            "SeCreateSymbolicLinkPrivilege). Symlink socket-parent guards stay covered on hosts " +
            "that can create links.",
    )
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
