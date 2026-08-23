package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.AgentAttach
import dev.sebastiano.spectre.agent.AttachInterruptedException
import dev.sebastiano.spectre.agent.AttachOptions
import dev.sebastiano.spectre.agent.AttachedAutomator
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.SpectreAgentException
import dev.sebastiano.spectre.agent.SpectreAttachException
import dev.sebastiano.spectre.agent.effectiveUdsPath
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
                    }) +
                    // Cold daemon / compile often exceeds a tight budget while the client stays
                    // alive — prefer prod-like launch when you control the build (#386).
                    " Prefer a prod-like launch (java -jar / installDist) over ./gradlew when " +
                    "possible; for Gradle, ensure the app has started (cold daemon + compile " +
                    "can take >15s) and that --app-name matches the main class.",
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
        /** Seam for tests; production always resolves through [effectiveUdsPath]. */
        resolveUdsPath: (Path?, Long) -> Path = ::effectiveUdsPath,
    ): AttachedAutomator {
        if (!process.isAlive && !gradleish) {
            throw processExited(process, stdoutPath, stderrPath)
        }
        // Pin UDS path for the whole stage (including pre-load retries). Resolving it can fail
        // when no default candidate fits sun_path (#442), and that failure has to arrive as an
        // AGENT_BOOTSTRAP LaunchAgentBootstrapException carrying the capture paths, like every
        // other bootstrap failure — not as a bare SpectreAttachException that skips the stage
        // taxonomy. The retry loop's own catch blocks are below and do not cover this statement.
        val udsPath =
            try {
                resolveUdsPath(attachOptions.udsPath, attachedPid)
            } catch (ex: SpectreAttachException) {
                throw LaunchAgentBootstrapException(
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause = ex,
                )
            }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(bootstrapTimeoutMs)
        var lastAttachFailure: SpectreAttachException? = null
        while (true) {
            val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMs <= 0L) {
                bootstrapFailureOrProcessExit(
                    process = process,
                    gradleish = gradleish,
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
                bootstrapFailureOrProcessExit(
                    process = process,
                    gradleish = gradleish,
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause = ex,
                )
            } catch (ex: SpectreAttachException) {
                // Cheap instantaneous check, kept ahead of the retry decision: an already-dead
                // process has nothing left to retry against. The grace-aware reclassification in
                // bootstrapFailureOrProcessExit covers the slower "still exiting" case, and is
                // deliberately not on this path so retries stay fast.
                rethrowIfProcessDied(process, gradleish, stdoutPath, stderrPath)
                lastAttachFailure = ex
                if (
                    !isPreLoadAttachRetryable(ex.message, ex.cause?.message) ||
                        System.nanoTime() >= deadline
                ) {
                    bootstrapFailureOrProcessExit(
                        process = process,
                        gradleish = gradleish,
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
                bootstrapFailureOrProcessExit(
                    process = process,
                    gradleish = gradleish,
                    attachedPid = attachedPid,
                    stdoutPath = stdoutPath,
                    stderrPath = stderrPath,
                    cause = ex,
                )
            }
        }
    }

    /**
     * True when an attach failure with [message] (and optional [causeMessage]) is a **short**
     * pre-`loadAgent` HotSpot race, and so safe to retry with the same UDS path.
     *
     * Deliberately does **not** treat every `AttachNotSupportedException` as retryable: HotSpot
     * also uses that type for its independent attach-socket wait timeout (~10s). Retrying after
     * that terminal timeout would start another full JDK handshake and blow the stage budget. Only
     * the documented "state is not ready…" race (and "no such process" pid churn) retries.
     *
     * Internal rather than private so tests that drive [AgentAttach.attach] directly — instead of
     * going through [awaitAgentBootstrap] — retry the same race against the same definition (#443).
     */
    internal fun isPreLoadAttachRetryable(message: String?, causeMessage: String?): Boolean {
        val msg = (message.orEmpty() + " " + causeMessage.orEmpty()).lowercase()
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

    /**
     * Terminal AGENT_BOOTSTRAP failure — unless the launched process was simply on its way out.
     *
     * [awaitProcessAlive] only samples the process for [SETTLE_MS], so a command that is still
     * starting up when the sample lands satisfies stage [LaunchStage.PROCESS_ALIVE] and the launch
     * walks on. When the attach then fails, a loaded host can leave the process a few hundred
     * milliseconds short of exiting, and an instantaneous liveness check calls it a bootstrap
     * failure. That hides the real cause: nothing could attach because the process was exiting.
     *
     * So wait up to [PROCESS_EXIT_GRACE_MS] for it to finish. If it does, report the honest stage
     * with its exit code and captured stderr; if it is still running, the bootstrap failure is
     * genuine and keeps its own taxonomy. The wait only ever happens on a failure path (#447).
     *
     * Gradle-ish launches are exempt: their client exiting is normal, and
     * [GRADLE_CLIENT_DEAD_BEFORE_APP_JVM] already covers the case where that matters.
     */
    internal fun bootstrapFailureOrProcessExit(
        process: Process,
        gradleish: Boolean,
        attachedPid: Long,
        stdoutPath: Path,
        stderrPath: Path,
        cause: Throwable,
        /** Seam for tests; production always waits [PROCESS_EXIT_GRACE_MS]. */
        graceMs: Long = PROCESS_EXIT_GRACE_MS,
    ): Nothing {
        if (!gradleish && exitedWithinGrace(process, graceMs)) {
            throw processExited(process, stdoutPath, stderrPath)
        }
        throw LaunchAgentBootstrapException(
            attachedPid = attachedPid,
            stdoutPath = stdoutPath,
            stderrPath = stderrPath,
            cause = cause,
        )
    }

    /**
     * True when [process] has already exited, or exits within [graceMs].
     *
     * Returns immediately for an already-dead process. An interrupt during the wait restores the
     * interrupt flag and answers from the current liveness rather than swallowing the signal.
     */
    internal fun exitedWithinGrace(process: Process, graceMs: Long): Boolean =
        try {
            process.waitFor(graceMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            !process.isAlive
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
                // #362: windows() may list displayable-but-not-yet-showing surfaces so it agrees
                // with allNodes(). FIRST_WINDOW promises a visible window for Robot/screenshot —
                // require isShowing (wire field; defaults true on older agents).
                if (automator.windows().any { it.isShowing }) return
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

    /**
     * How long a failed agent bootstrap waits for the launched process to finish exiting before
     * blaming itself (#447). Only spent on a failure path, and small next to the stage budgets it
     * disambiguates.
     */
    private const val PROCESS_EXIT_GRACE_MS: Long = 2_000
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
