@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.launch.LaunchSpec
import dev.sebastiano.spectre.agent.launch.LaunchStage
import dev.sebastiano.spectre.agent.launch.ProcessExitedBeforeAttachException
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ParameterResolver

/**
 * Lifecycle tests for the JUnit launch surface. Full UI attach e2e lives in `:agent`
 * (`LaunchAndAttachIntegrationTest`).
 */
class LaunchAndAttachExtensionTest {

    @Test
    fun `extension implements lifecycle callbacks and parameter resolver`() {
        // Reflective assignability so the contracts stay asserted without always-true smart casts.
        val extClass = LaunchAndAttachExtension::class.java
        assertTrue(BeforeEachCallback::class.java.isAssignableFrom(extClass))
        assertTrue(AfterEachCallback::class.java.isAssignableFrom(extClass))
        assertTrue(ParameterResolver::class.java.isAssignableFrom(extClass))
        // Constructor still callable with a minimal spec.
        LaunchAndAttachExtension(LaunchSpec(command = listOf(javaBin(), "-version")))
    }

    @Test
    fun `rule before launches and surfaces stage PROCESS_ALIVE for java -version`() {
        val captureDir = Files.createTempDirectory("spectre-testing-launch-rule-")
        val rule =
            LaunchAndAttachRule(
                LaunchSpec(command = listOf(javaBin(), "-version"), captureDirectory = captureDir)
            )
        val ex = assertFailsWith<ProcessExitedBeforeAttachException> { rule.startSession() }
        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
        assertTrue(Files.isRegularFile(ex.stderrPath))
        // stop is safe when start never stored a session.
        rule.stopSession()
    }

    @Test
    fun `rule launched accessor rejects out-of-lifecycle use`() {
        val rule = LaunchAndAttachRule(LaunchSpec(command = listOf(javaBin(), "-version")))
        assertFailsWith<IllegalStateException> { rule.launched }
        assertFailsWith<IllegalStateException> { rule.automator }
    }

    private fun javaBin(): String {
        val exe =
            if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
                "java.exe"
            } else {
                "java"
            }
        return Paths.get(System.getProperty("java.home"), "bin", exe).toString()
    }
}
