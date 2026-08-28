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
                    cause = attachNotSupportedCause(),
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

    @Test
    fun `the grace never outlives the caller's bootstrap-stage budget`() {
        // Codex P2: a fixed grace on top of an exhausted stage means the documented per-stage
        // timeout no longer bounds AGENT_BOOTSTRAP, and a caller with a short failure-detection
        // budget waits up to two extra seconds for its exception.
        val dying = attachNotSupportedCause()
        assertEquals(0L, LaunchReadiness.exitGraceMs(budgetRemainingMs = 0, cause = dying))
        assertEquals(0L, LaunchReadiness.exitGraceMs(budgetRemainingMs = -500, cause = dying))
        assertEquals(120L, LaunchReadiness.exitGraceMs(budgetRemainingMs = 120, cause = dying))
    }

    @Test
    fun `a no-live-agent failure spends remaining bootstrap budget rather than the 2s cap`() {
        assertEquals(
            60_000L,
            LaunchReadiness.exitGraceMs(
                budgetRemainingMs = 60_000,
                cause = attachNotSupportedCause(),
            ),
        )
    }

    @Test
    fun `PROCESS_EXIT_GRACE_MS is not raised`() {
        // #454 exists so this constant is never the thing we tune. Remaining agentBootstrapMs
        // and the failure kind decide the wait; this stays the #447 historical 2s cap.
        assertEquals(2_000L, LaunchReadiness.PROCESS_EXIT_GRACE_MS)
    }

    @Test
    fun `a process that exits after 2s but inside remaining bootstrap budget is PROCESS_ALIVE`() {
        // #454: hosted windows-latest can still be exiting `java -version` after the 2s #447 cap,
        // while remaining agentBootstrapMs (default 15s) has plenty of budget left. Attach never
        // obtained a live agent (AttachNotSupportedException), so the honest stage is
        // PROCESS_ALIVE.
        val cause = attachNotSupportedCause()
        val remainingBudgetMs = 5_000L
        val graceMs =
            LaunchReadiness.exitGraceMs(budgetRemainingMs = remainingBudgetMs, cause = cause)
        val ex =
            assertFailsWith<ProcessExitedBeforeAttachException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = FakeProcess(exitsAfterMs = AFTER_TWO_SECOND_CAP_MS),
                    gradleish = false,
                    attachedPid = 4321,
                    stdoutPath = captureFile("stdout"),
                    stderrPath = captureFile("stderr"),
                    cause = cause,
                    graceMs = graceMs,
                )
            }
        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
        assertTrue(
            graceMs > LaunchReadiness.PROCESS_EXIT_GRACE_MS,
            "derived bound must exceed the 2s cap so a slow exit can still be PROCESS_ALIVE; got $graceMs",
        )
        assertTrue(graceMs <= remainingBudgetMs)
    }

    @Test
    fun `an ordinary AgentLoadException does not spend remaining bootstrap budget`() {
        val cause =
            RuntimeException(
                "VirtualMachine.loadAgent(agent.jar) failed: AgentLoadException: not found",
                AgentLoadException("agent library failed to init"),
            )
        assertEquals(0L, LaunchReadiness.exitGraceMs(budgetRemainingMs = 5_000, cause = cause))
    }

    @Test
    fun `AgentLoadException that indicates a dying target spends remaining bootstrap budget`() {
        val cause =
            RuntimeException(
                "VirtualMachine.loadAgent(agent.jar) failed: AgentLoadException",
                AgentLoadException("Target VM did not respond"),
            )
        assertEquals(5_000L, LaunchReadiness.exitGraceMs(budgetRemainingMs = 5_000, cause = cause))
    }

    @Test
    fun `dynamic-agent-loading-disabled against a still-live process stays AGENT_BOOTSTRAP without waiting`() {
        // Definitive: a healthy JVM that refuses dynamic agents. Waiting remaining agentBootstrapMs
        // (or even the old 2s cap) only delays an answer we already have.
        val cause =
            RuntimeException(
                "The target JVM does not allow dynamic agent loading. Restart it with " +
                    "`-XX:+EnableDynamicAgentLoading` and retry the attach.",
                RuntimeException("Dynamic agent loading is not enabled"),
            )
        val remainingBudgetMs = 5_000L
        val graceMs =
            LaunchReadiness.exitGraceMs(budgetRemainingMs = remainingBudgetMs, cause = cause)
        assertEquals(0L, graceMs, "definitive failures must not spend remaining bootstrap budget")
        val startedAt = System.nanoTime()
        val ex =
            assertFailsWith<LaunchAgentBootstrapException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = FakeProcess(exitsAfterMs = 60_000),
                    gradleish = false,
                    attachedPid = 4321,
                    stdoutPath = captureFile("stdout"),
                    stderrPath = captureFile("stderr"),
                    cause = cause,
                    graceMs = remainingBudgetMs,
                )
            }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        assertEquals(LaunchStage.AGENT_BOOTSTRAP, ex.stage)
        assertTrue(
            elapsedMs < 1_000,
            "must not burn the derived bound on a definitive failure; elapsed=${elapsedMs}ms",
        )
    }

    @Test
    fun `an exhausted budget still reclassifies a process that has already exited`() {
        // Capping the grace must not cost the instantaneous case: a process that is already gone
        // is still a PROCESS_ALIVE failure, budget or no budget.
        val cause = attachNotSupportedCause()
        assertEquals(0L, LaunchReadiness.exitGraceMs(budgetRemainingMs = 0, cause = cause))
        val ex =
            assertFailsWith<ProcessExitedBeforeAttachException> {
                LaunchReadiness.bootstrapFailureOrProcessExit(
                    process = FakeProcess(exitsAfterMs = 0),
                    gradleish = false,
                    attachedPid = 4321,
                    stdoutPath = captureFile("stdout"),
                    stderrPath = captureFile("stderr"),
                    cause = cause,
                    graceMs = 0,
                )
            }
        assertEquals(LaunchStage.PROCESS_ALIVE, ex.stage)
    }

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

    /**
     * Local stand-ins so tests do not compile-depend on `jdk.attach`. Production matches these
     * types by simple name on the cause chain — the same names HotSpot throws.
     */
    private class AttachNotSupportedException(message: String) : RuntimeException(message)

    private class AgentLoadException(message: String) : RuntimeException(message)

    private fun attachNotSupportedCause(): RuntimeException =
        RuntimeException(
            "VirtualMachine.attach(4321) failed: AttachNotSupportedException: not attachable",
            AttachNotSupportedException("not attachable"),
        )

    private companion object {
        const val AFTER_TWO_SECOND_CAP_MS: Long = 2_100
    }
}
