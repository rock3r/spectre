package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class DaemonEndpointTest {
    @Test
    fun `places the default socket under the users spectre directory`() {
        assertEquals(
            Path.of("/home/alice/.spectre/daemon/daemon.sock"),
            DaemonEndpoint.defaultSocketPath(Path.of("/home/alice")),
        )
    }
}
