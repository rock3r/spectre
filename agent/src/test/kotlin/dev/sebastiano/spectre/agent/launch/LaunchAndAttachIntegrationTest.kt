@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Issue #208 acceptance e2es for the launch-and-attach harness.
 *
 * (a) Direct `java` launch of `:agent-test-fixture` — staged readiness, exercise, teardown. (c)
 * Binary that exits immediately → stage-1 taxonomy error with exit code + stderr.
 *
 * Gating matches [dev.sebastiano.spectre.agent.AgentAttachIntegrationTest]: Linux/macOS,
 * non-headless, agent runtime jar property set by `:agent:test`.
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class LaunchAndAttachIntegrationTest {

    @Test
    fun `direct java launch of fixture completes staged readiness exercise and tears down`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for Compose Desktop fixture",
        )
        val agentJar = locateAgentJarOrSkip()
        val captureDir = Files.createTempDirectory("spectre-launch-e2e-direct-")
        val attachedPid: Long

        LaunchAndAttach.launch(
                LaunchSpec(
                    command = fixtureJavaCommand(),
                    captureDirectory = captureDir,
                    stageTimeouts =
                        LaunchStageTimeouts(
                            processAliveMs = 5_000,
                            jvmAttachableMs = 15_000,
                            agentBootstrapMs = 20_000,
                            firstWindowMs = 30_000,
                        ),
                    attachOptions = AttachOptions(agentJarPath = agentJar, attachTimeoutMs = 20_000),
                )
            )
            .use { session ->
                assertFalse(session.gradleish)
                assertEquals(session.launchedPid, session.attachedPid)
                assertTrue(Files.isRegularFile(session.stdoutPath))
                assertTrue(Files.isRegularFile(session.stderrPath))

                val windows = session.automator.windows()
                assertTrue(
                    windows.isNotEmpty(),
                    "expected fixture window after staged readiness; got $windows",
                )
                val labels = session.automator.findByTestTag(TAG_LABEL)
                assertTrue(
                    labels.isNotEmpty(),
                    "expected fixture tag $TAG_LABEL after launch+attach",
                )
                attachedPid = session.attachedPid
            }

        // After close, the fixture process tree must be gone.
        assertFalse(
            ProcessHandle.of(attachedPid).map { it.isAlive }.orElse(false),
            "fixture pid $attachedPid should be dead after LaunchedSession.close()",
        )
    }

    @Test
    fun `command that exits immediately fails stage PROCESS_ALIVE with exit code and stderr`() {
        val captureDir = Files.createTempDirectory("spectre-launch-e2e-fail-")
        // Use a non-JVM binary that exits immediately with stderr — avoids races where a dying
        // HotSpot process is briefly attachable and fails as bootstrap instead of PROCESS_ALIVE.
        val command =
            listOf("/bin/sh", "-c", "echo 'spectre-launch-fail-fast-stderr' 1>&2; exit 17")

        val ex =
            assertFailsWith<ProcessExitedBeforeAttachException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = command,
                        captureDirectory = captureDir,
                        stageTimeouts =
                            LaunchStageTimeouts(
                                processAliveMs = 5_000,
                                jvmAttachableMs = 5_000,
                                agentBootstrapMs = 5_000,
                                firstWindowMs = 5_000,
                            ),
                    )
                )
            }

        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
        assertEquals(17, ex.exitCode)
        assertTrue(
            Files.isRegularFile(ex.stdoutPath),
            "stdout capture file must exist: ${ex.stdoutPath}",
        )
        assertTrue(
            Files.isRegularFile(ex.stderrPath),
            "stderr capture file must exist: ${ex.stderrPath}",
        )
        val stderr = Files.readString(ex.stderrPath)
        assertTrue(
            stderr.contains("spectre-launch-fail-fast-stderr") ||
                ex.stderrExcerpt.contains("spectre-launch-fail-fast-stderr"),
            "expected captured stderr content; path=${ex.stderrPath} excerpt='${ex.stderrExcerpt}'",
        )
        assertTrue(
            ex.message!!.contains("17") || ex.message!!.contains("exit"),
            "message should carry exit code: ${ex.message}",
        )
        assertTrue(
            ex.message!!.contains("PROCESS_ALIVE") ||
                ex.message!!.contains(LaunchStage.PROCESS_ALIVE.name),
            "message should identify stage: ${ex.message}",
        )
    }

    @Test
    fun `java -version exits as stage PROCESS_ALIVE with stderr capture files`() {
        // Complements the rewriter unit tests that assert -XX:+EnableDynamicAgentLoading injection
        // on the command line. This path proves capture files + stage taxonomy for a fast-exit
        // java tool (exit code may be 0).
        val captureDir = Files.createTempDirectory("spectre-launch-inject-")
        val javaBin = javaBin()
        val ex =
            assertFailsWith<ProcessExitedBeforeAttachException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = listOf(javaBin, "-version"),
                        captureDirectory = captureDir,
                        injectDynamicAgentLoading = true,
                    )
                )
            }
        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
        assertTrue(Files.isRegularFile(ex.stderrPath))
        assertTrue(
            Files.size(ex.stderrPath) > 0 || ex.stderrExcerpt.isNotBlank(),
            "java -version should have produced stderr capture",
        )
    }

    private fun fixtureJavaCommand(): List<String> {
        val javaBin = javaBin()
        val classpath = System.getProperty("java.class.path")
        return listOf(
            javaBin,
            "-cp",
            classpath,
            // Harness injects EnableDynamicAgentLoading; do not set it here so e2e proves
            // injection.
            "-Djava.awt.headless=false",
            "-Dcompose.application.configure.swing.globals=true",
            "-Dapple.awt.UIElement=false",
            "dev.sebastiano.spectre.agent.fixture.ComposeFixtureMainKt",
        )
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

    private fun locateAgentJarOrSkip(): Path {
        val prop = System.getProperty("dev.sebastiano.spectre.agent.runtimeJar")
        assumeFalse(
            prop.isNullOrBlank(),
            "Requires -Ddev.sebastiano.spectre.agent.runtimeJar; :agent:test sets it.",
        )
        val path = Paths.get(prop!!)
        assumeFalse(!Files.isRegularFile(path), "Agent runtime JAR not found at $path")
        return path
    }
}
