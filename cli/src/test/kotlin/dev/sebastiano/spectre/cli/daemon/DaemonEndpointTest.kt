package dev.sebastiano.spectre.cli.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonEndpointTest {
    @Test
    fun `uses a short deterministic per-user directory under the posix temp base`() {
        assertEquals(
            "/tmp/sp-d-2bd806c9/daemon.sock",
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
}
