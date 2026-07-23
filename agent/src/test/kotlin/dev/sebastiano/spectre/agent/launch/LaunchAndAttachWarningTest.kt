@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit-level proof that Gradle-ish launches emit the loud warning through
 * [LaunchAndAttach.launch]'s warning sink before process failure (no UI required).
 */
class LaunchAndAttachWarningTest {

    @Test
    fun `gradleish command emits warning via sink even when process fails fast`() {
        val captureDir = Files.createTempDirectory("spectre-launch-warn-")
        // Use a non-existent gradlew path so start fails or exits immediately — we only care
        // that detection + warning fire before readiness completes.
        val fakeGradlew =
            Paths.get(captureDir.toString(), "gradlew").also {
                Files.writeString(it, "#!/bin/sh\necho gradle-fake-stderr >&2\nexit 42\n")
                it.toFile().setExecutable(true)
            }
        val warnings = mutableListOf<String>()
        // Use an impossible nameFilter so discovery cannot attach to an unrelated daemon-child JVM
        // on a busy developer machine; we only care that the warning fires.
        val ex =
            assertFailsWith<LaunchException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = listOf(fakeGradlew.toString(), ":app:run"),
                        captureDirectory = captureDir,
                        appJvmNameFilter = "NoSuchMainClass_issue208_warning_test",
                        stageTimeouts =
                            LaunchStageTimeouts(
                                processAliveMs = 2_000,
                                jvmAttachableMs = 2_000,
                                agentBootstrapMs = 2_000,
                                firstWindowMs = 2_000,
                            ),
                    ),
                    warningSink = { warnings += it },
                )
            }
        assertTrue(warnings.isNotEmpty(), "expected at least one warning before failure")
        assertTrue(
            warnings.any { it.contains("Gradle-ish", ignoreCase = true) },
            "warnings=$warnings",
        )
        assertTrue(
            warnings.any { it.contains("daemon", ignoreCase = true) },
            "warnings should mention daemon; got $warnings",
        )
        // Client dies before any app JVM — should report PROCESS_ALIVE with exit, not a
        // name-filter miss at JVM_ATTACHABLE.
        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage, "unexpected stage: ${ex.message}")
        assertTrue(ex is ProcessExitedBeforeAttachException, "expected process-exit taxonomy")
    }
}
