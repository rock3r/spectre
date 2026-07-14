package dev.sebastiano.spectre.cli.daemon

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonClientTest {
    @Test
    fun `starts a detached daemon process for the first request`() {
        val socketPath = temporaryDaemonClientSocketPath()
        var process: Process? = null
        var shutdownConfirmed = false

        try {
            DaemonClient(socketPath).use { client ->
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    client.requestOrStart(DaemonRequest.ListSessions) {
                        process =
                            DaemonProcessLauncher(
                                    socketPath = socketPath,
                                    classPath = testRuntimeClassPath(),
                                )
                                .start()
                    },
                )
                assertEquals(DaemonResponse.ShuttingDown, client.request(DaemonRequest.Shutdown))
                shutdownConfirmed = true
            }

            awaitDaemonClientSocketRemoval(socketPath)
        } finally {
            if (!shutdownConfirmed) process?.destroyForcibly()?.waitFor()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `starts the daemon then sends the first request when the socket is absent`() {
        val socketPath = temporaryDaemonClientSocketPath()
        var server: DaemonServer? = null
        var starts = 0

        try {
            DaemonClient(socketPath).use { client ->
                assertEquals(
                    DaemonResponse.Sessions(emptyList()),
                    client.requestOrStart(DaemonRequest.ListSessions) {
                        starts++
                        server = DaemonServer(socketPath)
                    },
                )
            }

            assertEquals(1, starts)
        } finally {
            server?.close()
            server?.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `sends a handshake before forwarding requests to the daemon`() {
        val socketPath = temporaryDaemonClientSocketPath()
        val server = DaemonServer(socketPath, registry = testDaemonSessionRegistry())

        try {
            DaemonClient(socketPath).use { client ->
                assertEquals(
                    DaemonResponse.Attached(sessionId = "pid-1234", targetPid = 1234),
                    client.request(DaemonRequest.Attach(1234)),
                )
                assertEquals(
                    DaemonResponse.Sessions(
                        sessions =
                            listOf(DaemonSessionSummary(sessionId = "pid-1234", targetPid = 1234))
                    ),
                    client.request(DaemonRequest.ListSessions),
                )
            }
        } finally {
            server.close()
            server.awaitTermination()
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }
}

private fun temporaryDaemonClientSocketPath(): Path =
    if ("posix" in FileSystems.getDefault().supportedFileAttributeViews()) {
        Path.of("/tmp", "sp-d-${UUID.randomUUID().toString().take(8)}", "daemon", "daemon.sock")
    } else {
        Files.createTempDirectory("spectre-daemon-test").resolve("daemon").resolve("daemon.sock")
    }

private fun deleteTemporaryDaemonClientSocketPath(socketPath: Path) {
    Files.deleteIfExists(socketPath)
    Files.deleteIfExists(socketPath.parent)
    Files.deleteIfExists(socketPath.parent.parent)
}

private fun awaitDaemonClientSocketRemoval(socketPath: Path) {
    repeat(MAX_SOCKET_REMOVAL_ATTEMPTS) {
        if (!Files.exists(socketPath)) return
        Thread.sleep(SOCKET_REMOVAL_WAIT_MILLIS)
    }
    error("Timed out waiting for daemon socket removal")
}

private const val MAX_SOCKET_REMOVAL_ATTEMPTS: Int = 100
private const val SOCKET_REMOVAL_WAIT_MILLIS: Long = 10

private fun testRuntimeClassPath(): String =
    requireNotNull(System.getProperty("spectre.cli.testRuntimeClasspath")) {
        "Missing CLI test runtime classpath"
    }

private fun testDaemonSessionRegistry(): DaemonSessionRegistry = DaemonSessionRegistry {
    AutoCloseable {}
}
