package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DaemonEndpointTest {
    @Test
    fun `uses a short deterministic per-user directory under the posix temp base`() {
        assertEquals(
            Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1.sock").toString(),
            DaemonEndpoint.defaultSocketPath(
                    osName = "Mac OS X",
                    tempDirectory = "/var/folders/long",
                    userName = "alice",
                )
                .toString(),
        )
    }

    @Test
    fun `rejects a socket path too long for unix domain sockets`() {
        assertFailsWith<IllegalArgumentException> {
            DaemonEndpoint.defaultSocketPath(
                osName = "Windows 11",
                tempDirectory = "C:\\Users\\${"a".repeat(80)}\\AppData\\Local\\Temp",
                userName = "alice",
            )
        }
    }

    // Absolute on every host (`/spectre-sock` or `C:\\spectre-sock`), so the override passes the
    // absolute-path check wherever the test runs.
    private val overrideDirectory: Path = Path.of("").toAbsolutePath().root.resolve("spectre-sock")

    @Test
    fun `SPECTRE_DAEMON_SOCKET_DIR replaces the default base directory`() {
        for (osName in listOf("Linux", "Mac OS X", "Windows 11")) {
            assertEquals(
                overrideDirectory.resolve("sp-d-2bd806c9").resolve("daemon-v1.sock"),
                DaemonEndpoint.defaultSocketPath(
                    osName = osName,
                    tempDirectory = "/tmp",
                    userName = "alice",
                    socketDirectoryOverride = overrideDirectory.toString(),
                ),
                osName,
            )
        }
    }

    @Test
    fun `the daemon binds the override endpoint the client resolved`() {
        // The daemon never resolves the endpoint itself: the client passes its resolved path as
        // --socket, so client and daemon agree on SPECTRE_DAEMON_SOCKET_DIR by construction.
        val clientEndpoint =
            DaemonEndpoint.defaultSocketPath(
                osName = "Linux",
                tempDirectory = "/tmp",
                userName = "alice",
                socketDirectoryOverride = overrideDirectory.toString(),
            )
        val daemonCommand =
            DaemonProcessLauncher(
                    socketPath = clientEndpoint,
                    javaExecutable = "/jdk/bin/java",
                    classPath = "spectre.jar",
                    readProperty = { null },
                    callerJvmOptions = { emptyList() },
                )
                .command()

        assertEquals(clientEndpoint, DaemonMain.socketPath(daemonCommand))
    }

    @Test
    fun `a blank SPECTRE_DAEMON_SOCKET_DIR keeps the default base directory`() {
        assertEquals(
            Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1.sock"),
            DaemonEndpoint.defaultSocketPath(
                osName = "Linux",
                tempDirectory = "/tmp",
                userName = "alice",
                socketDirectoryOverride = "  ",
            ),
        )
    }

    @Test
    fun `a relative SPECTRE_DAEMON_SOCKET_DIR is rejected`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                DaemonEndpoint.defaultSocketPath(
                    osName = "Linux",
                    tempDirectory = "/tmp",
                    userName = "alice",
                    socketDirectoryOverride = "relative/dir",
                )
            }
        assertEquals(
            "SPECTRE_DAEMON_SOCKET_DIR must be an absolute path, got 'relative/dir'",
            failure.message,
        )
    }

    @Test
    fun `a socket path over the limit names the path and its length`() {
        val directory = overrideDirectory.resolve("a".repeat(80)).toString()
        val expectedPath = Path.of(directory, "sp-d-2bd806c9", "daemon-v1.sock").toString()
        val failure =
            assertFailsWith<IllegalArgumentException> {
                DaemonEndpoint.defaultSocketPath(
                    osName = "Linux",
                    tempDirectory = "/tmp",
                    userName = "alice",
                    socketDirectoryOverride = directory,
                )
            }
        val message = failure.message.orEmpty()
        assertTrue(
            message.startsWith(
                "Daemon socket path $expectedPath is ${expectedPath.length} bytes, over the " +
                    "100-byte limit for Unix domain sockets."
            ),
            message,
        )
        assertTrue("SPECTRE_DAEMON_SOCKET_DIR" in message, message)
    }

    @Test
    fun `legacy endpoints follow SPECTRE_DAEMON_SOCKET_DIR too`() {
        assertEquals(
            overrideDirectory.resolve("sp-d-2bd806c9").resolve("daemon-v1-1.sock"),
            DaemonEndpoint.legacySocketPaths(
                    osName = "Linux",
                    tempDirectory = "/tmp",
                    userName = "alice",
                    socketDirectoryOverride = overrideDirectory.toString(),
                )
                .last(),
        )
    }

    @Test
    fun `discovers prior minor-version sockets during the stable endpoint migration`() {
        assertEquals(
            listOf(
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-12.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-11.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-10.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-9.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-8.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-7.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-6.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-5.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-4.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-3.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-2.sock"),
                Path.of("/tmp", "sp-d-2bd806c9", "daemon-v1-1.sock"),
            ),
            DaemonEndpoint.legacySocketPaths(
                osName = "Mac OS X",
                tempDirectory = "/var/folders/long",
                userName = "alice",
            ),
        )
    }
}
