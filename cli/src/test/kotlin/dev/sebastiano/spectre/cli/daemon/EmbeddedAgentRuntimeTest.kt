package dev.sebastiano.spectre.cli.daemon

import java.io.ByteArrayInputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EmbeddedAgentRuntimeTest {
    @Test
    fun `installs each embedded agent version at a content-addressed path`() {
        val home = Files.createTempDirectory("spectre-runtime-home")

        val first = EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(1, 2)) }
        val second = EmbeddedAgentRuntime.install(home) { ByteArrayInputStream(byteArrayOf(3, 4)) }

        assertNotEquals(first, second)
        assertEquals(listOf<Byte>(1, 2), Files.readAllBytes(first).toList())
        assertEquals(listOf<Byte>(3, 4), Files.readAllBytes(second).toList())
    }
}
