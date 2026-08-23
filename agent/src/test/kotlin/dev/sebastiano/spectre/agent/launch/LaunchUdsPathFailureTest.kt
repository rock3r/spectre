@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.UdsPathTooLongException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * `LaunchAndAttach.launch` promises [LaunchAgentBootstrapException] for every AGENT_BOOTSTRAP
 * failure, carrying the stage and the captured stdout/stderr paths callers use to diagnose it.
 *
 * The UDS path is pinned before the retry loop, outside its `try`, so a path that fails the
 * `sun_path` guard would escape that contract as a bare [UdsPathTooLongException] — no stage, no
 * capture paths. Raised as P2 by Codex review on PR #445.
 */
class LaunchUdsPathFailureTest {

    @Test
    fun `an unusable default UDS path fails the launch stage rather than escaping it`() {
        // The default-path branch is the one Codex flagged: on Windows every candidate can
        // overflow sun_path, and that resolution happens before the retry loop's own try/catch.
        // It cannot be provoked on POSIX, where the only candidate is the hard-coded /tmp, so the
        // resolver is injected here.
        val captureDir = Files.createTempDirectory("spectre-launch-uds-default-")
        val stdoutPath = Files.createFile(captureDir.resolve("stdout.log"))
        val stderrPath = Files.createFile(captureDir.resolve("stderr.log"))
        val tooLong = Path.of("/tmp", "sp-" + "x".repeat(200), "agent.sock")

        val ex =
            assertFailsWith<LaunchAgentBootstrapException> {
                LaunchReadiness.awaitAgentBootstrap(
                    process = ProcessBuilder("sleep", "5").start(),
                    attachedPid = 1L,
                    gradleish = true,
                    attachOptions = AttachOptions(),
                    bootstrapTimeoutMs = 1_000,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    resolveUdsPath = { _, _ -> throw UdsPathTooLongException(listOf(tooLong)) },
                )
            }

        assertIs<UdsPathTooLongException>(ex.cause, "the real cause must survive the wrapping")
        assertEquals(stdoutPath, ex.stdoutPath)
        assertEquals(stderrPath, ex.stderrPath)
        assertEquals(LaunchStage.AGENT_BOOTSTRAP, ex.stage)
    }

    @Test
    fun `an unusable UDS path fails the launch stage rather than escaping it`() {
        val captureDir = Files.createTempDirectory("spectre-launch-uds-")
        val stdoutPath = captureDir.resolve("stdout.log")
        val stderrPath = captureDir.resolve("stderr.log")
        Files.createFile(stdoutPath)
        Files.createFile(stderrPath)
        val tooLong = Path.of("/tmp", "sp-" + "x".repeat(200), "agent.sock")

        val ex =
            assertFailsWith<LaunchAgentBootstrapException> {
                LaunchReadiness.awaitAgentBootstrap(
                    process = ProcessBuilder("sleep", "5").start(),
                    attachedPid = 1L,
                    // `gradleish` skips the liveness precondition, which is not what this covers.
                    gradleish = true,
                    attachOptions = AttachOptions(udsPath = tooLong),
                    bootstrapTimeoutMs = 1_000,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                )
            }

        assertIs<UdsPathTooLongException>(ex.cause, "the real cause must survive the wrapping")
        assertEquals(stdoutPath, ex.stdoutPath)
        assertEquals(stderrPath, ex.stderrPath)
        assertEquals(LaunchStage.AGENT_BOOTSTRAP, ex.stage)
    }
}
