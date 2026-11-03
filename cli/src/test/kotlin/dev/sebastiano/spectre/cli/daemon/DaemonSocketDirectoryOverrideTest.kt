package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [DaemonServer] with a socket below a `SPECTRE_DAEMON_SOCKET_DIR` override. */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonSocketDirectoryOverrideTest {
    @Test
    fun `refuses a SPECTRE_DAEMON_SOCKET_DIR override below a writable ancestor`() {
        if ("posix" !in FileSystems.getDefault().supportedFileAttributeViews()) return

        // The override goes through the same ancestor checks as the default /tmp base.
        // Created under /tmp so the socket path stays short on macOS too.
        val ancestor = Files.createTempDirectory(Path.of("/tmp"), "sp-o")
        Files.setPosixFilePermissions(ancestor, PosixFilePermissions.fromString("rwxrwxrwx"))
        val socketPath =
            DaemonEndpoint.defaultSocketPath(
                osName = "Linux",
                tempDirectory = "/tmp",
                userName = "alice",
                socketDirectoryOverride = ancestor.toString(),
            )

        try {
            val failure = assertFailsWith<IOException> { DaemonServer(socketPath) }
            assertTrue(
                "must not be group or world writable" in failure.message.orEmpty(),
                failure.message,
            )
            assertFalse(Files.exists(socketPath.parent))
        } finally {
            Files.deleteIfExists(socketPath)
            Files.deleteIfExists(socketPath.parent)
            Files.deleteIfExists(ancestor)
        }
    }
}
