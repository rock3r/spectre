@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WindowsAttachHelperTest {
    @Test
    fun `helper launches when application class path does not contain Spectre`() {
        val original = System.getProperty("java.class.path")
        val missing = Path.of(System.getProperty("java.io.tmpdir"), "not-a-spectre-classpath")
        try {
            System.setProperty("java.class.path", missing.toString())
            val process =
                ProcessBuilder(WindowsAttachHelper.command(Long.MAX_VALUE, missing, "args")).start()
            assertTrue(process.waitFor(10, TimeUnit.SECONDS))
            val output = process.inputStream.bufferedReader().readText().trim()
            assertEquals(2, process.exitValue(), output)
            assertNotNull(WindowsAttachHelper.failure(output))
        } finally {
            System.setProperty("java.class.path", original)
        }
    }
}
