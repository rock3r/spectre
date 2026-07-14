package dev.sebastiano.spectre.cli.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

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
    fun `uses the windows temp directory on windows`() {
        assertEquals(
            "C:\\Users\\alice\\AppData\\Local\\Temp",
            DaemonEndpoint.baseDirectory(
                osName = "Windows 11",
                tempDirectory = "C:\\Users\\alice\\AppData\\Local\\Temp",
            ),
        )
    }
}
