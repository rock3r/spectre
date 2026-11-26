@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir

@EnabledOnOs(OS.LINUX)
class LinuxProcessArgumentsTest {
    @Test
    fun `long classpath preserves exact marker tokens`(@TempDir directory: Path) {
        val marker = UUID.randomUUID().toString()
        val decoy = UUID.randomUUID().toString()
        val key = LaunchAndAttach.LAUNCH_MARKER_PROPERTY
        val classes = Path.of(SameMain::class.java.protectionDomain.codeSource.location.toURI())
        val classPath =
            List(1 + 12000 / classes.toString().length) { classes }.joinToString(File.pathSeparator)
        val ready = directory.resolve("fixture.ready")
        val process =
            ProcessBuilder(
                    ProcessHandle.current().info().command().orElseThrow(),
                    "-cp",
                    classPath,
                    "-Dfixture.decoy=-D$key=$decoy",
                    "-D$key=$marker",
                    SameMain::class.java.name,
                    ready.toString(),
                    ProcessHandle.current().pid().toString(),
                    key,
                )
                .start()
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (!Files.exists(ready) && process.isAlive && System.nanoTime() < deadline) Thread
                .sleep(10)
            assertEquals(listOf(process.pid().toString(), marker), Files.readAllLines(ready))
            assertTrue(hasLaunchMarker(process.pid(), marker))
            assertFalse(hasLaunchMarker(process.pid(), decoy))
            assertFalse(hasLaunchMarker(process.pid(), marker.dropLast(1)))
        } finally {
            process.destroyForcibly().waitFor()
        }
    }
}
