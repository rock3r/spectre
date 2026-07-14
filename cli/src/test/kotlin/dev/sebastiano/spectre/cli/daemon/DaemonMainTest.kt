package dev.sebastiano.spectre.cli.daemon

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DaemonMainTest {
    @Test
    fun `parses the required socket argument`() {
        assertEquals(
            Path.of("/tmp/spectre.sock"),
            DaemonMain.socketPath(listOf("--socket", "/tmp/spectre.sock")),
        )
    }

    @Test
    fun `rejects missing socket argument`() {
        assertFailsWith<IllegalArgumentException> { DaemonMain.socketPath(emptyList()) }
    }
}
