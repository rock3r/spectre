@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GradleLaunchMarkerTest {
    @Test
    fun `a marked Java child requires the exact invocation property`() {
        val source = Files.createTempDirectory("spectre-owner-").resolve("MarkerFixture.java")
        Files.writeString(
            source,
            "class MarkerFixture { public static void main(String[] a) throws Exception { System.in.read(); } }",
        )
        val marker = java.util.UUID.randomUUID().toString()
        val process =
            ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-D${LaunchAndAttach.LAUNCH_MARKER_PROPERTY}=$marker",
                    source.toString(),
                )
                .redirectErrorStream(true)
                .start()
        try {
            // The process is alive as soon as start returns. Its native argv is already available.
            assertFalse(hasLaunchMarker(process.pid(), marker.dropLast(1)))
            assertTrue(hasLaunchMarker(process.pid(), marker))
        } finally {
            process.destroyForcibly().waitFor()
            source.parent.toFile().deleteRecursively()
        }
        assertFalse(hasLaunchMarker(process.pid(), marker))
    }

    @Test
    fun `Gradle launch marker is injected into the JavaExec argv instead of JAVA_TOOL_OPTIONS`() {
        val captureDir = Files.createTempDirectory("spectre-marker-command-")
        try {
            val prepared =
                prepareGradleLaunchCommand(
                    command = listOf("./gradlew", ":app:run", "--no-daemon"),
                    captureDirectory = captureDir,
                    marker = "launch-proof",
                )

            assertEquals("./gradlew", prepared.first())
            assertEquals("--init-script", prepared[1])
            assertEquals(":app:run", prepared[3])
            assertEquals("--no-daemon", prepared[4])
            val concurrent =
                prepareGradleLaunchCommand(listOf("./gradlew", ":app:run"), captureDir, "another")
            assertFalse(
                prepared[2] == concurrent[2],
                "concurrent launches must own separate scripts",
            )
            val script = Path.of(prepared[2]).readText()
            assertTrue(script.contains("JavaExec"))
            assertTrue(
                script.contains("-Ddev.sebastiano.spectre.launch.id=launch-proof"),
                "marker must become a real JavaExec JVM argument: $script",
            )
        } finally {
            captureDir.toFile().deleteRecursively()
        }
    }
}
