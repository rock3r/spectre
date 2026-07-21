package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

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

    @Test
    fun `discovers prior minor-version sockets during the stable endpoint migration`() {
        assertEquals(
            listOf(
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
