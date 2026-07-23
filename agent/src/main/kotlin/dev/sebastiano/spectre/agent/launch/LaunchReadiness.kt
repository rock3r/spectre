package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachInterruptedException
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAgentException
import dev.sebastiano.spectre.agent.SpectreAttachException
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Per-stage readiness polls for [LaunchAndAttach]. */
@ExperimentalSpectreAgentApi
internal object LaunchReadiness {

    /**
     * Stage [LaunchStage.PROCESS_ALIVE]: the process must still be running after start. Polls for
     * up to [timeoutMs] so an early crash is attributed here rather than a later stage.
     */
    fun awaitProcessAlive(process: Process, timeoutMs: Long, stdoutPath: Path, stderrPath: Path) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        // Minimum settle so a process that dies mid-classload still surfaces as stage-1 even when
        // the caller passes a very small timeout.
        val settleDeadline =
            System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(minOf(timeoutMs, SETTLE_MS))
        while (System.nanoTime() < deadline) {
            if (!process.isAlive) {
                throw processExited(process, stdoutPath, stderrPath)
            }
            // After the short settle window, process-alive is satisfied; remaining budget is for
            // "still alive when we leave this stage", not for waiting the full timeout on success.
            if (System.nanoTime() >= settleDeadline) return
            sleepQuietly(POLL_MS)
        }
        if (!process.isAlive) {
            throw processExited(process, stdoutPath, stderrPath)
        }
    }

    fun awaitJvmAttachable(
        process: Process,
        launchedPid: Long,
        gradleish: Boolean,
        nameFilter: String?,
        timeoutMs: Long,
        stdoutPath: Path,
        stderrPath: Path,
    ): Long {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (!process.isAlive && !gradleish) {
                throw processExited(process, stdoutPath, stderrPath)
            }
            val pid =
                if (gradleish) {
                    LaunchDescendantDiscovery.discoverAppJvm(launchedPid, nameFilter)
                } else if (LaunchDescendantDiscovery.isJvmAttachable(launchedPid)) {
                    launchedPid
                } else {
                    null
                }
            if (pid != null) return pid
            sleepQuietly(POLL_MS)
        }
        if (!process.isAlive && !gradleish) {
            throw processExited(process, stdoutPath, stderrPath)
        }
        throw JvmNotAttachableException(
            launchedPid = launchedPid,
            timeoutMs = timeoutMs,
            stdoutPath = stdoutPath,
            stderrPath = stderrPath,
            detail =
                if (gradleish) {
                    "Gradle-ish launch: no descendant app JVM matched" +
                        (nameFilter?.let { " nameFilter='$it'" }.orEmpty())
                } else {
                    "pid $launchedPid never appeared in VirtualMachine.list()"
                },
        )
    }

    /**
     * Stage [LaunchStage.AGENT_BOOTSTRAP]: a **single** [AgentAttach.attach] with the stage budget
     * as `attachTimeoutMs`.
     *
     * Retries are intentionally avoided: each attach call may `loadAgent` with a UDS path, and a
     * second attempt with a freshly generated path would wait forever while the already-loaded
     * agent remains bound to the first socket (Codex P1). Stage 2 already waits for the JVM to
     * appear in the Attach list before we get here.
     */
    fun awaitAgentBootstrap(
        process: Process,
        attachedPid: Long,
        gradleish: Boolean,
        attachOptions: AttachOptions,
        bootstrapTimeoutMs: Long,
        stdoutPath: Path,
        stderrPath: Path,
    ): AttachedAutomator {
        if (!process.isAlive && !gradleish) {
            throw processExited(process, stdoutPath, stderrPath)
        }
        // Pin UDS path for the whole stage (even if a future retry is reintroduced).
        val udsPath = attachOptions.udsPath ?: AttachOptions.defaultUdsPath(attachedPid)
        val options = attachOptions.copy(udsPath = udsPath, attachTimeoutMs = bootstrapTimeoutMs)
        return try {
            AgentAttach.attach(attachedPid, options)
        } catch (ex: AttachInterruptedException) {
            Thread.currentThread().interrupt()
            throw LaunchAgentBootstrapException(
                attachedPid = attachedPid,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                cause = ex,
            )
        } catch (ex: SpectreAgentException) {
            throw LaunchAgentBootstrapException(
                attachedPid = attachedPid,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                cause = ex,
            )
        } catch (ex: SpectreAttachException) {
            throw LaunchAgentBootstrapException(
                attachedPid = attachedPid,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                cause = ex,
            )
        } catch (ex: IOException) {
            throw LaunchAgentBootstrapException(
                attachedPid = attachedPid,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                cause = ex,
            )
        }
    }

    fun awaitFirstWindow(
        automator: AttachedAutomator,
        attachedPid: Long,
        timeoutMs: Long,
        stdoutPath: Path,
        stderrPath: Path,
    ) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var lastError: IOException? = null
        while (System.nanoTime() < deadline) {
            try {
                if (automator.windows().isNotEmpty()) return
            } catch (ex: IOException) {
                lastError = ex
            }
            sleepQuietly(POLL_MS)
        }
        throw FirstWindowTimeoutException(
            attachedPid = attachedPid,
            timeoutMs = timeoutMs,
            stdoutPath = stdoutPath,
            stderrPath = stderrPath,
            cause = lastError,
        )
    }

    fun processExited(
        process: Process,
        stdoutPath: Path,
        stderrPath: Path,
    ): ProcessExitedBeforeAttachException {
        val exitCode =
            try {
                process.exitValue()
            } catch (_: IllegalThreadStateException) {
                -1
            }
        return ProcessExitedBeforeAttachException(
            exitCode = exitCode,
            stderrExcerpt = readExcerpt(stderrPath),
            stdoutPath = stdoutPath,
            stderrPath = stderrPath,
        )
    }

    private fun readExcerpt(path: Path, maxChars: Int = STDERR_EXCERPT_CHARS): String {
        if (!Files.isRegularFile(path)) return ""
        return try {
            val text = Files.readString(path, StandardCharsets.UTF_8)
            if (text.length <= maxChars) text else text.take(maxChars) + "\n…(truncated)…"
        } catch (_: IOException) {
            ""
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            throw ex
        }
    }

    private const val POLL_MS: Long = 50
    private const val SETTLE_MS: Long = 250
    private const val STDERR_EXCERPT_CHARS: Int = 4_096
}
