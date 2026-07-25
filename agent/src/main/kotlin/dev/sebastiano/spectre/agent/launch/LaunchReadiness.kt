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
            if (!gradleish) {
                // Direct: client is the target. Stage PROCESS_ALIVE established the process is
                // live; attach-by-pid works even when -XX:-UsePerfData hides the target from
                // VirtualMachine.list() (see SpectreProcesses).
                if (!process.isAlive) {
                    throw processExited(process, stdoutPath, stderrPath)
                }
                return launchedPid
            }
            // Gradle-ish: keep discovering after the client exits. The app JVM is often a
            // daemon child (not a ProcessHandle descendant of ./gradlew); tasks that fork and
            // return still need the remaining stage budget to observe the app.
            val pid = LaunchDescendantDiscovery.discoverAppJvm(launchedPid, nameFilter)
            if (pid != null) return pid
            sleepQuietly(POLL_MS)
        }
        if (!gradleish) {
            if (!process.isAlive) {
                throw processExited(process, stdoutPath, stderrPath)
            }
            throw JvmNotAttachableException(
                launchedPid = launchedPid,
                timeoutMs = timeoutMs,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                detail =
                    "pid $launchedPid stayed alive but was not attachable within ${timeoutMs}ms",
            )
        }
        // Gradle-ish timeout: classify by whether the *client* is still running.
        // Dead client + no app → surface client exit/stderr (wrapper/env failure).
        // Live client + no app → true discovery / name-filter miss.
        if (!process.isAlive) {
            throw processExited(
                process = process,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                detail = GRADLE_CLIENT_DEAD_BEFORE_APP_JVM,
            )
        }
        throw JvmNotAttachableException(
            launchedPid = launchedPid,
            timeoutMs = timeoutMs,
            stdoutPath = stdoutPath,
            stderrPath = stderrPath,
            detail =
                "Gradle-ish launch: client still running but no daemon-child/client-descendant " +
                    "app JVM matched" +
                    (nameFilter?.let { " nameFilter='$it'" }.orEmpty()) +
                    (if (nameFilter.isNullOrBlank()) {
                        " (set LaunchSpec.appJvmNameFilter / --app-name to disambiguate " +
                            "daemon children)"
                    } else {
                        ""
                    }),
        )
    }

    /**
     * Stage [LaunchStage.AGENT_BOOTSTRAP]: [AgentAttach.attach] with the stage budget as
     * `attachTimeoutMs`.
     *
     * **UDS path is pinned** for the whole stage: once `loadAgent` has bound an agent to a socket,
     * a second attempt with a different path would wait forever (Codex P1). Retries are limited to
     * pre-load failures where HotSpot refuses attach ("state is not ready…") — common on macOS CI
     * when `VirtualMachine.list()` surfaces a JVM a few hundred ms before the attach handshake is
     * open. Those retries never reach `loadAgent`, so the pinned path stays safe.
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
        // Pin UDS path for the whole stage (including pre-load retries).
        val udsPath = attachOptions.udsPath ?: AttachOptions.defaultUdsPath(attachedPid)
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bootstrapTimeoutMs)
        var lastAttachFailure: SpectreAttachException? = null
        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0L) {
                throw LaunchAgentBootstrapException(
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause =
                        lastAttachFailure
                            ?: IllegalStateException(
                                "Agent bootstrap budget ${bootstrapTimeoutMs}ms exhausted " +
                                    "before attach for pid=$attachedPid"
                            ),
                )
            }
            // Bound each attempt by remaining stage budget so pre-load retries cannot
            // stack a full attachTimeoutMs UDS wait after the stage deadline.
            val options = attachOptions.copy(udsPath = udsPath, attachTimeoutMs = remainingMs)
            try {
                return AgentAttach.attach(attachedPid, options)
            } catch (ex: AttachInterruptedException) {
                Thread.currentThread().interrupt()
                throw LaunchAgentBootstrapException(
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause = ex,
                )
            } catch (ex: SpectreAgentException) {
                rethrowIfProcessDied(process, gradleish, stdoutPath, stderrPath)
                throw LaunchAgentBootstrapException(
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause = ex,
                )
            } catch (ex: SpectreAttachException) {
                rethrowIfProcessDied(process, gradleish, stdoutPath, stderrPath)
                lastAttachFailure = ex
                if (!isPreLoadAttachRetryable(ex) || System.nanoTime() >= deadline) {
                    throw LaunchAgentBootstrapException(
                        attachedPid = attachedPid,
                        stdoutPath = stdoutPath,
                        stderrPath = stderrPath,
                        cause = ex,
                    )
                }
                try {
                    sleepQuietly(POLL_MS)
                } catch (interrupted: InterruptedException) {
                    // Preserve AGENT_BOOTSTRAP taxonomy when backoff is interrupted
                    // (same wrapping as AttachInterruptedException during attach).
                    Thread.currentThread().interrupt()
                    throw LaunchAgentBootstrapException(
                        attachedPid = attachedPid,
                        stdoutPath = stdoutPath,
                        stderrPath = stderrPath,
                        cause = AttachInterruptedException(udsPath, interrupted),
                    )
                }
            } catch (ex: IOException) {
                rethrowIfProcessDied(process, gradleish, stdoutPath, stderrPath)
                throw LaunchAgentBootstrapException(
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause = ex,
                )
            }
        }
    }

    /**
     * True when [ex] is a **short** pre-`loadAgent` HotSpot race (safe to retry with the same UDS
     * path).
     *
     * Deliberately does **not** treat every `AttachNotSupportedException` as retryable: HotSpot
     * also uses that type for its independent attach-socket wait timeout (~10s). Retrying after
     * that terminal timeout would start another full JDK handshake and blow the stage budget. Only
     * the documented "state is not ready…" race (and "no such process" pid churn) retries.
     */
    private fun isPreLoadAttachRetryable(ex: SpectreAttachException): Boolean {
        val msg = (ex.message.orEmpty() + " " + (ex.cause?.message.orEmpty())).lowercase()
        return "not ready to participate in attach handshake" in msg || "no such process" in msg
    }

    /** Prefer stage PROCESS_ALIVE when the process died during attach (race with early exit). */
    private fun rethrowIfProcessDied(
        process: Process,
        gradleish: Boolean,
        stdoutPath: Path,
        stderrPath: Path,
    ) {
        if (!gradleish && !process.isAlive) {
            throw processExited(process, stdoutPath, stderrPath)
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
            if (!ProcessHandle.of(attachedPid).map { it.isAlive }.orElse(false)) {
                throw FirstWindowTimeoutException(
                    attachedPid = attachedPid,
                    timeoutMs = timeoutMs,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause =
                        IllegalStateException(
                            "Attached pid=$attachedPid exited before any window appeared"
                        ),
                )
            }
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
        detail: String = "",
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
            detail = detail,
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

    /**
     * Surfaced when a Gradle-ish `./gradlew` client exits during descendant discovery before any
     * app JVM is found. Prefer this over a name-filter [JvmNotAttachableException] so wrapper
     * download / env failures are not misread as discovery misconfiguration.
     */
    internal const val GRADLE_CLIENT_DEAD_BEFORE_APP_JVM: String =
        "Gradle client exited before any app JVM was discovered " +
            "(wrapper download failure, bad env, or build error are common causes — see stderr)"
}
