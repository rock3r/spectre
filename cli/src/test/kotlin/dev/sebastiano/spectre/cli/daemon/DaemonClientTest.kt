package dev.sebastiano.spectre.cli.daemon

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonClientTest {
    @Test
    fun `sends a handshake before forwarding requests to the daemon`() {
        val socketPath = temporaryDaemonClientSocketPath()
        val server = DaemonServer(socketPath)

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
