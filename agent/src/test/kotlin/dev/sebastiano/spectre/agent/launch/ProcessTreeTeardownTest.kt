@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

class ProcessTreeTeardownTest {

    @Test
    fun `destroyTreeByPid is no-op when handle is missing`() {
        // Extremely unlikely to be a live process; of() empty → treated as already dead.
        assertTrue(ProcessTreeTeardown.destroyTreeByPid(pid = Long.MAX_VALUE / 4))
    }

    @Test
    fun `destroyTree is idempotent for a process that already exited`() {
        val javaBin =
            Paths.get(System.getProperty("java.home"), "bin", javaExecutableName()).toString()
        val process =
            ProcessBuilder(javaBin, "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        process.waitFor(5, TimeUnit.SECONDS)
        assertFalse(process.isAlive)
        assertTrue(ProcessTreeTeardown.destroyTree(process.toHandle()))
    }

    @Test
    fun `destroyTree kills a long-running process`() {
        assumeTrue(
            !System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
            "Uses /bin/sleep; Windows path covered by integration teardown e2e",
        )
        val process =
            ProcessBuilder("/bin/sleep", "3600")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        try {
            assertTrue(process.isAlive, "sleep helper should still be running")
            assertTrue(ProcessTreeTeardown.destroyTree(process.toHandle(), graceMs = 500))
            // ProcessHandle may observe death slightly before Process.isAlive flips on some hosts.
            assertTrue(
                process.waitFor(2, TimeUnit.SECONDS),
                "process should exit after destroyTree",
            )
            assertFalse(process.isAlive, "process should be dead after destroyTree")
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun javaExecutableName(): String =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            "java.exe"
        } else {
            "java"
        }
}
