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
 * #386: Gradle-ish JVM_ATTACHABLE timeout remains actionable — stage name, name filter, and
 * prod-like launch guidance. Uses a short-lived sleep client so the test stays fast; the expanded
 * default 120s budget is covered by [LaunchStageTimeoutsTest] + [LaunchAndAttach] wiring.
 */
class LaunchAndAttachJvmAttachableTimeoutTest {

    @Test
    fun `gradleish live client timeout message is actionable`() {
        val captureDir = Files.createTempDirectory("spectre-launch-jvm-attach-timeout-")
        val isWindows =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        // Stay alive longer than the stage budget so we hit JVM_ATTACHABLE (client live, no
        // matching app), not PROCESS_ALIVE. Command basename must look Gradle-ish.
        val command =
            if (isWindows) {
                val bat =
                    Paths.get(captureDir.toString(), "gradlew.bat").also {
                        // Prefer ping over `timeout`: under non-console redirected I/O, Windows
                        // `timeout` can abort immediately (same as
                        // LaunchAndAttachGradleClientDeathTest).
                        // ~30s live client so we hit JVM_ATTACHABLE, not PROCESS_ALIVE.
                        Files.writeString(
                            it,
                            """
                            @echo off
                            ping -n 31 127.0.0.1 >nul
                            """
                                .trimIndent() + "\r\n",
                        )
                    }
                listOf(bat.toString(), ":app:run")
            } else {
                val sh =
                    Paths.get(captureDir.toString(), "gradlew").also {
                        Files.writeString(it, "#!/bin/sh\nsleep 30\n")
                        it.toFile().setExecutable(true)
                    }
                listOf(sh.toString(), ":app:run")
            }
        val filter = "NoSuchMainClass_issue386_timeout"
        val ex =
            assertFailsWith<JvmNotAttachableException> {
                LaunchAndAttach.launch(
                    LaunchSpec(
                        command = command,
                        captureDirectory = captureDir,
                        appJvmNameFilter = filter,
                        stageTimeouts =
                            LaunchStageTimeouts(
                                processAliveMs = 2_000,
                                jvmAttachableMs = 1_500,
                                agentBootstrapMs = 2_000,
                                firstWindowMs = 2_000,
                            ),
                    )
                )
            }
        assertEquals(LaunchStage.JVM_ATTACHABLE, ex.stage)
        assertEquals(1_500, ex.timeoutMs)
        val msg = ex.message!!
        assertTrue(msg.contains("JVM_ATTACHABLE"), "stage in message: $msg")
        assertTrue(msg.contains("1500") || msg.contains("1_500"), "timeout in message: $msg")
        assertTrue(msg.contains(filter) || msg.contains("nameFilter"), "name filter: $msg")
        assertTrue(
            msg.contains("prod-like", ignoreCase = true) ||
                msg.contains("java -jar", ignoreCase = true) ||
                msg.contains("installDist", ignoreCase = true),
            "prefer non-Gradle guidance: $msg",
        )
        assertTrue(
            msg.contains("app-name", ignoreCase = true) ||
                msg.contains("--app-name") ||
                msg.contains("nameFilter"),
            "app-name guidance: $msg",
        )
    }
}
