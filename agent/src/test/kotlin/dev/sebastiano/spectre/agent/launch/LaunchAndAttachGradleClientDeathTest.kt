@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a Gradle-ish `./gradlew` client dies before any app JVM is discovered (wrapper download
 * failure, bad env, etc.), the failure must surface client exit + stderr — not a misleading
 * [JvmNotAttachableException] / name-filter miss.
 *
 * Discovery still polls for the full [LaunchStageTimeouts.jvmAttachableMs] after the client exits
 * (daemon-spawned apps), then classifies dead-client + no match as PROCESS_ALIVE.
 */
class LaunchAndAttachGradleClientDeathTest {

    @Test
    fun `gradle client death during discovery is PROCESS_ALIVE with exit and stderr not name-filter`() {
        val captureDir = Files.createTempDirectory("spectre-launch-gradle-client-death-")
        // Survive PROCESS_ALIVE settle (~250ms), die during discovery with distinctive stderr.
        // jvmAttachable budget is intentionally longer than the client lifetime so classification
        // happens after discovery had a chance to find a (non-matching) app JVM.
        val fakeGradlew = writeFakeGradlewClient(captureDir)

        val ex =
            assertFailsWith<ProcessExitedBeforeAttachException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = listOf(fakeGradlew.toString(), ":app:run"),
                        captureDirectory = captureDir,
                        // Impossible filter so we never attach an unrelated daemon-child JVM.
                        appJvmNameFilter = "NoSuchMainClass_issue208_client_death_test",
                        stageTimeouts =
                            LaunchStageTimeouts(
                                processAliveMs = 500,
                                jvmAttachableMs = 4_000,
                                agentBootstrapMs = 2_000,
                                firstWindowMs = 2_000,
                            ),
                    )
                )
            }

        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
        assertEquals(42, ex.exitCode)
        assertTrue(
            ex.stderrExcerpt.contains("spectre-gradle-client-death-stderr") ||
                Files.readString(ex.stderrPath).contains("spectre-gradle-client-death-stderr"),
            "expected client stderr in exception; excerpt='${ex.stderrExcerpt}' path=${ex.stderrPath}",
        )
        assertTrue(
            ex.message!!.contains("Gradle client", ignoreCase = true) ||
                ex.message!!.contains("app JVM", ignoreCase = true),
            "message should explain gradle client died before app JVM; got: ${ex.message}",
        )
        assertFalse(
            ex.message!!.contains("nameFilter", ignoreCase = true),
            "must not blame nameFilter when the Gradle client already exited; got: ${ex.message}",
        )
        assertFalse(
            ex.message!!.contains("JVM_ATTACHABLE", ignoreCase = true),
            "must not report stage JVM_ATTACHABLE; got: ${ex.message}",
        )
    }

    private fun writeFakeGradlewClient(captureDir: Path): Path {
        val windows =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        return if (windows) {
            Paths.get(captureDir.toString(), "gradlew.bat").also {
                // ~1s delay then exit 42; stderr via stderr stream.
                Files.writeString(
                    it,
                    // Prefer ping over `timeout`: under non-console redirected I/O, Windows
                    // `timeout` can abort immediately (Bugbot/Windows CI).
                    """
                    @echo off
                    echo spectre-gradle-client-death-stderr 1>&2
                    ping -n 2 127.0.0.1 >nul
                    exit /b 42
                    """
                        .trimIndent() + "\r\n",
                )
            }
        } else {
            Paths.get(captureDir.toString(), "gradlew").also {
                Files.writeString(
                    it,
                    """
                    #!/bin/sh
                    echo 'spectre-gradle-client-death-stderr' 1>&2
                    sleep 1
                    exit 42
                    """
                        .trimIndent() + "\n",
                )
                it.toFile().setExecutable(true)
            }
        }
    }
}
