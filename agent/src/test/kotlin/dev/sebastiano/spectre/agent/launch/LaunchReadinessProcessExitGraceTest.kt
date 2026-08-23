@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the short exit grace that keeps the launch stage taxonomy honest (#447).
 *
 * [LaunchReadiness.awaitProcessAlive] only samples the launched process for a settle window, so a
 * command that is still starting up when the sample lands satisfies stage `PROCESS_ALIVE` and the
 * launch walks on to `AGENT_BOOTSTRAP`. When the attach then fails because that command is on its
 * way out, the honest answer is still "the process exited before anything could attach" — so the
 * bootstrap failure path gives it a moment to finish exiting before assigning blame.
 */
class LaunchReadinessProcessExitGraceTest {

    @Test
    fun `an already-dead process is reported without waiting`() {
        val process = FakeProcess(exitsAfterMs = 0)
        val startedAt = System.nanoTime()
        assertTrue(LaunchReadiness.exitedWithinGrace(process, graceMs = 5_000))
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertTrue(elapsedMs < 1_000, "should not burn the grace on an already-dead process")
    }

    @Test
    fun `a process that exits inside the grace window counts as exited`() {
        // The #447 shape: alive when the stage sampled it, gone a moment later.
        assertTrue(
            LaunchReadiness.exitedWithinGrace(FakeProcess(exitsAfterMs = 150), graceMs = 5_000)
        )
    }

    @Test
    fun `a process still running after the grace window does not count as exited`() {
        // A genuinely broken bootstrap against a healthy app JVM must keep its own taxonomy.
        assertFalse(
            LaunchReadiness.exitedWithinGrace(FakeProcess(exitsAfterMs = 60_000), graceMs = 100)
        )
    }

    @Test
    fun `a bootstrap failure against a process that is still exiting is reported as PROCESS_ALIVE`() {
        // The #447 shape on hosted windows-latest: `cmd.exe … exit /b 17` and `java -version` are
        // both still alive when the attach fails (AttachNotSupportedException / AgentLoadException
        // respectively), so an instantaneous liveness check blames AGENT_BOOTSTRAP for a process
        // that was simply on its way out.
        val ex =
            assertFailsWith<ProcessExitedBeforeAttachException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = FakeProcess(exitsAfterMs = 150),
                    gradleish = false,
                    attachedPid = 4321,
                    stdoutPath = captureFile("stdout"),
                    stderrPath = captureFile("stderr"),
                    cause = IllegalStateException("attach failed while the process was exiting"),
                    graceMs = 5_000,
                )
            }
        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
        assertEquals(17, ex.exitCode)
    }

    @Test
    fun `a bootstrap failure against a healthy process keeps stage AGENT_BOOTSTRAP`() {
        // A genuinely broken bootstrap against an app JVM that stays up must not be relabelled.
        val ex =
            assertFailsWith<LaunchAgentBootstrapException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = FakeProcess(exitsAfterMs = 60_000),
                    gradleish = false,
                    attachedPid = 4321,
                    stdoutPath = captureFile("stdout"),
                    stderrPath = captureFile("stderr"),
                    cause = IllegalStateException("agent jar rejected"),
                    graceMs = 100,
                )
            }
        assertEquals(LaunchStage.AGENT_BOOTSTRAP, ex.stage)
    }

    @Test
    fun `a gradle-ish launch keeps stage AGENT_BOOTSTRAP even when its client has exited`() {
        // For Gradle-ish launches the tracked process is the client, whose exit is normal — the
        // app JVM lives on as a daemon child. Reclassifying there would be wrong.
        val ex =
            assertFailsWith<LaunchAgentBootstrapException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = FakeProcess(exitsAfterMs = 0),
                    gradleish = true,
                    attachedPid = 4321,
                    stdoutPath = captureFile("stdout"),
                    stderrPath = captureFile("stderr"),
                    cause = IllegalStateException("agent runtime never bound the UDS path"),
                    graceMs = 5_000,
                )
            }
        assertEquals(LaunchStage.AGENT_BOOTSTRAP, ex.stage)
    }

    private fun captureFile(suffix: String): Path =
        Files.createTempFile("spectre-exit-grace-", "-$suffix.log")

    /** Minimal [Process] whose liveness flips after a fixed wall-clock delay. */
    private class FakeProcess(private val exitsAfterMs: Long) : Process() {
        private val startedAt = System.nanoTime()

        private fun hasExited(): Boolean =
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) >= exitsAfterMs

        override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()

        override fun getInputStream(): InputStream = InputStream.nullInputStream()

        override fun getErrorStream(): InputStream = InputStream.nullInputStream()

        override fun waitFor(): Int {
            while (!hasExited()) Thread.sleep(POLL_MS)
            return EXIT_CODE
        }

        override fun exitValue(): Int =
            if (hasExited()) EXIT_CODE else throw IllegalThreadStateException()

        override fun destroy() = Unit

        override fun isAlive(): Boolean = !hasExited()

        private companion object {
            const val POLL_MS: Long = 10
            const val EXIT_CODE: Int = 17
        }
    }
}
