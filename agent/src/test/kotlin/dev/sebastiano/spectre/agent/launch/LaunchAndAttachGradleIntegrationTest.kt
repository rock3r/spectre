@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreProcesses
import dev.sebastiano.spectre.agent.fixture.TAG_LABEL
import java.awt.GraphicsEnvironment
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Issue #208 acceptance (b): Gradle-run launch through the harness.
 *
 * Asserts loud Gradle warning, app JVM discovery (not daemon), attach, exercise, and teardown that
 * kills the app JVM while leaving any Gradle daemon alive.
 *
 * Gated like other agent UI e2es. Skips when no `gradlew` is found (e.g. incomplete checkout).
 */
@EnabledOnOs(OS.LINUX, OS.MAC)
class LaunchAndAttachGradleIntegrationTest {

    @Test
    fun `gradle fixture run warns discovers app attaches and tears down without killing daemon`() {
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Requires non-headless JVM for Compose Desktop fixture",
        )
        val agentJar = locateAgentJarOrSkip()
        val repoRoot = locateRepoRootOrSkip()
        val gradlew = repoRoot.resolve("gradlew")
        assumeTrue(Files.isRegularFile(gradlew), "gradlew not found at $gradlew")
        assumeTrue(Files.isExecutable(gradlew) || true, "gradlew present")

        val daemonsBefore = gradleDaemonPids()
        val captureDir = Files.createTempDirectory("spectre-launch-e2e-gradle-")
        val warnings = mutableListOf<String>()
        val attachedPid: Long

        LaunchAndAttach.launch(
                LaunchSpec(
                    command = listOf(gradlew.toString(), ":agent-test-fixture:run", "-q"),
                    workingDirectory = repoRoot,
                    captureDirectory = captureDir,
                    appJvmNameFilter = "ComposeFixtureMain",
                    stageTimeouts =
                        LaunchStageTimeouts(
                            processAliveMs = 10_000,
                            jvmAttachableMs = 120_000,
                            agentBootstrapMs = 30_000,
                            firstWindowMs = 60_000,
                        ),
                    attachOptions = AttachOptions(agentJarPath = agentJar, attachTimeoutMs = 15_000),
                ),
                warningSink = { warnings += it },
            )
            .use { session ->
                assertTrue(session.gradleish, "expected Gradle-ish launch detection")
                assertTrue(
                    warnings.any { it.contains("Gradle-ish", ignoreCase = true) },
                    "expected loud Gradle warning; got $warnings",
                )
                assertTrue(
                    warnings.any { it.contains("daemon", ignoreCase = true) },
                    "warning should mention daemon caveats",
                )
                // Attached pid must not be a Gradle daemon.
                val attachedDisplay =
                    SpectreProcesses.listJvmProcesses()
                        .firstOrNull { it.pid == session.attachedPid }
                        ?.displayName
                        .orEmpty()
                assertFalse(
                    LaunchDescendantDiscovery.isGradleDaemonDisplayName(attachedDisplay),
                    "must not attach to Gradle daemon; displayName='$attachedDisplay'",
                )
                assertTrue(
                    session.attachedPid != session.launchedPid ||
                        attachedDisplay.contains("ComposeFixture", ignoreCase = true),
                    "attached pid should be the app JVM (displayName='$attachedDisplay')",
                )

                val windows = session.automator.windows()
                assertTrue(windows.isNotEmpty(), "expected fixture windows after Gradle launch")
                assertTrue(
                    session.automator.findByTestTag(TAG_LABEL).isNotEmpty(),
                    "expected fixture tag $TAG_LABEL",
                )
                attachedPid = session.attachedPid
            }

        assertFalse(
            ProcessHandle.of(attachedPid).map { it.isAlive }.orElse(false),
            "app JVM pid $attachedPid should be dead after session close",
        )
        // Daemons that existed before (or a daemon started for this run when not --no-daemon)
        // must not all be wiped. With --no-daemon there may be none; with a pre-existing
        // daemon pool, those PIDs should still be alive.
        val daemonsAfter = gradleDaemonPids()
        val stillAlive = daemonsBefore.filter { it in daemonsAfter }
        if (daemonsBefore.isNotEmpty()) {
            assertTrue(
                stillAlive.isNotEmpty(),
                "expected pre-existing Gradle daemon(s) to survive teardown; " +
                    "before=$daemonsBefore after=$daemonsAfter",
            )
        }
    }

    private fun gradleDaemonPids(): Set<Long> =
        runCatching { SpectreProcesses.listJvmProcesses() }
            .getOrDefault(emptyList())
            .filter { LaunchDescendantDiscovery.isGradleDaemonDisplayName(it.displayName) }
            .map { it.pid }
            .toSet()

    private fun locateRepoRootOrSkip(): Path {
        // Walk up from user.dir looking for settings.gradle.kts + gradlew.
        var dir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        repeat(8) {
            if (
                Files.isRegularFile(dir.resolve("settings.gradle.kts")) &&
                    Files.isRegularFile(dir.resolve("gradlew"))
            ) {
                return dir
            }
            dir = dir.parent ?: return@repeat
        }
        assumeTrue(false, "Could not locate repo root with gradlew from user.dir")
        error("unreachable")
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
