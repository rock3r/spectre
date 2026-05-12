package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Window-targeted recorder backed by ScreenCaptureKit via the bundled `spectre-screencapture` Swift
 * helper.
 *
 * Lifecycle:
 * 1. [start] extracts the helper binary, stamps a unique `Spectre/<id>` suffix on the target
 *    window's title (so the helper can find that exact window across the process boundary), builds
 *    the helper argv, and spawns the subprocess.
 * 2. The recorder waits a short startup probe to catch the common fast-fail exit codes
 *    ([HELPER_EXIT_BAD_ARGS], [HELPER_EXIT_WINDOW_NOT_FOUND], [HELPER_EXIT_TCC_DENIED]) and
 *    surfaces them with mapped error messages — restoring the title on failure so the user's app
 *    isn't left with a stale Spectre suffix.
 * 3. The returned [RecordingHandle] stops the helper by writing `q` to its stdin (mirrors
 *    `FfmpegRecorder`'s shutdown contract). Title restoration happens unconditionally during stop,
 *    including the crashed-mid-recording branch.
 *
 * Implements [WindowRecorder] (window-targeted) rather than the region-based
 * [dev.sebastiano.spectre.recording.Recorder] — ScreenCaptureKit is fundamentally window-attached
 * and coordinates aren't necessary. The high-level [dev.sebastiano.spectre.recording.AutoRecorder]
 * takes both backends and picks per call: SCK when there's a window on macOS, ffmpeg otherwise.
 *
 * macOS-only. On non-macOS hosts the helper isn't bundled; [start] fails early with a clear "helper
 * not found" error from [HelperBinaryExtractor].
 */
public class ScreenCaptureKitRecorder
internal constructor(
    private val helperExtractor: HelperBinaryExtractor,
    private val processFactory: ProcessFactory,
) : WindowRecorder {

    public constructor() : this(HelperBinaryExtractor(), SystemProcessFactory)

    override fun start(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        // Resolve everything that can fail without touching window state first. Anything we do
        // before `discriminator.apply()` doesn't need a restore path; anything after must
        // restore on every error branch (the original window title is held by the
        // discriminator and would otherwise stay dirty across a failed start).
        val helperPath = helperExtractor.extract()
        Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())

        val discriminator = TitleDiscriminator(window)
        discriminator.apply()
        // Single restore-on-failure point — flipped to true on the success path so the handle
        // takes ownership of the discriminator. Avoids per-throw-site restore() calls and the
        // associated "did we cover every exception type from ProcessBuilder.start" gotcha.
        var success = false
        try {
            val argv =
                HelperArguments(
                        pid = windowOwnerPid,
                        titleContains = discriminator.value,
                        output = output,
                        fps = options.frameRate,
                        captureCursor = options.captureCursor,
                        discoveryTimeoutMs = DEFAULT_DISCOVERY_TIMEOUT_MS,
                    )
                    .toArgv(helperPath)

            val process = processFactory.start(argv)

            // Wait for the helper to either:
            //   - emit the READY marker on stdout (window discovered, SCK is streaming
            //     frames), or
            //   - exit with one of the documented fast-fail codes (2/3/4/5).
            // A plain `process.waitFor(STARTUP_PROBE_MILLIS)`-style probe was racy: when the
            // helper's window discovery took longer than the probe, start() reported success
            // before the helper had actually started capturing. Reading stdout removes the
            // race.
            val ready =
                try {
                    awaitHelperReady(process, READY_WAIT_MILLIS)
                } catch (e: InterruptedException) {
                    // destroyForcibly() is asynchronous on the JVM. Without a bounded wait
                    // here we can return from start() while the helper is still alive +
                    // writing the output file — leaks a subprocess and leaves the .mov
                    // mutating after the caller thinks startup aborted. Mirror the non-ready
                    // path's bounded wait so the helper is genuinely gone by the time we
                    // propagate the interrupt.
                    process.destroyForcibly()
                    @Suppress("SwallowedException")
                    try {
                        process.waitFor(FORCE_KILL_DURING_START_SECONDS, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        // A second interrupt during cleanup means the JVM is being torn down
                        // anyway — daemon-process semantics will let the helper die with us.
                    }
                    Thread.currentThread().interrupt()
                    throw e
                }
            if (!ready) {
                val exit =
                    if (process.isAlive) {
                        process.destroyForcibly()
                        // Bounded wait — destroyForcibly() is asynchronous and an unbounded
                        // `waitFor()` can pin the calling thread indefinitely if the kernel
                        // hasn't reaped the helper yet. If the helper still hasn't died after
                        // the timeout, fall back to a sentinel exit code so we don't propagate
                        // a hang into the caller.
                        val terminated =
                            try {
                                process.waitFor(FORCE_KILL_DURING_START_SECONDS, TimeUnit.SECONDS)
                            } catch (e: InterruptedException) {
                                Thread.currentThread().interrupt()
                                throw e
                            }
                        if (terminated) process.exitValue() else EXIT_HELPER_NOT_REAPED
                    } else {
                        process.exitValue()
                    }
                throw IllegalStateException(messageForHelperExit(exit, output, argv))
            }

            val handle =
                ScreenCaptureKitRecordingHandle(
                    process = process,
                    output = output,
                    discriminator = discriminator,
                )
            success = true
            return handle
        } finally {
            if (!success) discriminator.restore()
        }
    }

    /**
     * Reads the helper's stdout line by line until we see [READY_MARKER] (success) or stdin closes
     * (the helper has exited and we'll surface its exit code at the call site).
     *
     * Returns `true` if the marker was seen within [budgetMs]; `false` if either the helper exited
     * or the budget elapsed without a marker. The reading is deliberately blocking on a new
     * platform thread so we don't have to touch `process.inputStream` from this thread directly —
     * the JDK's process input stream's `readLine` blocks indefinitely without a timeout primitive
     * of its own.
     */
    private fun awaitHelperReady(process: Process, budgetMs: Long): Boolean {
        val ready = AtomicBoolean(false)
        val readerStarted = CountDownLatch(1)
        val reader =
            Thread.ofPlatform().name("spectre-sck-helper-ready-reader").daemon(true).start {
                readerStarted.countDown()
                process.inputStream.bufferedReader().use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: return@start
                        if (line.trim() == READY_MARKER) {
                            ready.set(true)
                            return@start
                        }
                    }
                }
            }
        readerStarted.await()
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            if (ready.get()) return true
            if (!process.isAlive) {
                // Process has exited — give the reader thread a brief window to drain any
                // bytes already in the inputStream pipe buffer before deciding ready vs.
                // failure. Without this join, the reader can be mid-read of "READY\n" when
                // we observe the dead process and (incorrectly) report a failure.
                reader.join(READER_DRAIN_MILLIS)
                return ready.get()
            }
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        // Timeout — best-effort to interrupt the reader so it doesn't outlive the recorder.
        reader.interrupt()
        return ready.get()
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    public interface ProcessFactory {
        public fun start(argv: List<String>): Process
    }

    internal object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            ProcessBuilder(argv)
                // Pipe stdout so the JVM can read the helper's READY marker; the helper
                // writes nothing else to stdout so the pipe buffer stays small.
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                // Inherit stderr so the helper's diagnostic counters and any error messages
                // surface in the host JVM's stderr without us needing a reader thread to drain
                // the pipe. The helper writes only short single-line messages here, so there
                // is no risk of saturating the parent's stderr.
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
    }

    internal companion object {
        // `const val` members of any companion — including an `internal` one — are emitted
        // by the Kotlin compiler as `public static final` JVM fields on the outer class and
        // therefore appear in the ABI baseline. The const-vals below live at file level as
        // `private const val` to keep them out of the public surface (same shape as the
        // `STORE_KEY` fix in `testing/.../ComposeAutomatorExtension.kt`). `messageForHelperExit`
        // and `messageForHelperExitDuringRecording` are functions, which the Kotlin compiler
        // does keep behind the companion's `internal` visibility, so they stay here.

        fun messageForHelperExit(exit: Int, output: Path, argv: List<String>): String =
            when (exit) {
                HELPER_EXIT_BAD_ARGS ->
                    "spectre-screencapture rejected its arguments (exit 2). Argv: $argv"
                HELPER_EXIT_WINDOW_NOT_FOUND ->
                    "spectre-screencapture could not find the target window within the discovery " +
                        "timeout (exit 3). The window may not be on screen, or its title may have " +
                        "been changed before the helper had time to scan. Argv: $argv"
                HELPER_EXIT_TCC_DENIED ->
                    "spectre-screencapture was denied Screen Recording permission (exit 4). " +
                        "Grant the JVM Screen Recording under System Settings → Privacy & Security " +
                        "and restart the host process. Argv: $argv"
                HELPER_EXIT_PIPELINE ->
                    "spectre-screencapture's capture pipeline failed during start (exit 5) — " +
                        "output at $output is in an undefined state. Argv: $argv"
                EXIT_HELPER_NOT_REAPED ->
                    "spectre-screencapture did not exit after destroyForcibly() within " +
                        "${FORCE_KILL_DURING_START_SECONDS}s during start failure recovery — " +
                        "process may be leaked. Argv: $argv"
                else ->
                    "spectre-screencapture exited with code $exit during startup — recording did " +
                        "not start. Argv: $argv"
            }
    }
}

private class ScreenCaptureKitRecordingHandle(
    private val process: Process,
    override val output: Path,
    private val discriminator: TitleDiscriminator,
) : RecordingHandle {

    private val stopInitiated = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val result = AtomicReference<Result<Unit>?>()

    override val isStopped: Boolean
        get() = result.get()?.isSuccess == true

    override fun stop() {
        if (!stopInitiated.compareAndSet(false, true)) {
            finished.await()
            result.get()?.getOrThrow()
            return
        }
        // Run both `stopInternal()` and `discriminator.restore()` even if the first one
        // throws — restoring the window title is part of the cleanup contract even on the
        // crash branch, otherwise the user's window stays dirtied with the Spectre/<id>
        // suffix forever after a failed recording.
        //
        // Both outcomes are folded into a single `Result` published BEFORE `countDown` so
        // every concurrent caller (the one that won the CAS race AND the ones blocked on
        // `finished.await()`) observes the same outcome — including any restore failure.
        // Earlier shape stored success before restore, so a later restore failure was visible
        // to the first caller but invisible to subsequent ones; that inconsistency is gone.
        var primary: Throwable? = null
        @Suppress("TooGenericExceptionCaught")
        try {
            stopInternal()
        } catch (t: Throwable) {
            primary = t
        }
        // `stopInternal` re-sets the thread's interrupt flag if any of its `waitFor` calls saw
        // an interrupt — that's correct behaviour for propagating cancellation to the caller.
        // But `discriminator.restore()` in the production AWT adapter goes through
        // `EventQueue.invokeAndWait`, whose internal `Object.wait` throws InterruptedException
        // immediately when the interrupt flag is set. That would skip title restoration on
        // every interrupted stop and leave the window dirty with a `Spectre/<id>` suffix
        // forever. Clear the flag around restore, then re-set it so the caller still observes
        // cancellation. (Unit tests miss this because FakeTitledWindow bypasses the EDT.)
        val wasInterrupted = Thread.interrupted()
        @Suppress("TooGenericExceptionCaught")
        try {
            discriminator.restore()
        } catch (t: Throwable) {
            if (primary == null) primary = t else primary.addSuppressed(t)
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
        val outcome: Result<Unit> =
            if (primary == null) Result.success(Unit) else Result.failure(primary)
        result.set(outcome)
        finished.countDown()
        primary?.let { throw it }
    }

    private fun stopInternal() {
        if (!process.isAlive) {
            failIfHelperCrashed()
            return
        }
        @Suppress("TooGenericExceptionCaught")
        try {
            process.outputStream?.use { it.write('q'.code) }
        } catch (_: IOException) {
            // helper may have already exited; the waitFor below confirms.
        } catch (t: Throwable) {
            if (t is InterruptedException) Thread.currentThread().interrupt()
        }
        var interrupted = false
        val gracefulExit =
            try {
                process.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
        if (!gracefulExit) {
            process.destroy()
            val terminatedAfterDestroy =
                try {
                    process.waitFor(FORCE_KILL_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
            if (!terminatedAfterDestroy) {
                process.destroyForcibly()
                try {
                    process.waitFor(FORCE_KILL_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (process.isAlive) {
            throw IllegalStateException(
                "spectre-screencapture did not exit after destroyForcibly() within ${FORCE_KILL_SECONDS}s — " +
                    "output at $output is in an undefined state."
            )
        }
        failIfHelperCrashed()
    }

    private fun failIfHelperCrashed() {
        val exit =
            @Suppress("TooGenericExceptionCaught")
            try {
                process.exitValue()
            } catch (_: Throwable) {
                return
            }
        if (exit == 0) return
        // Any non-zero exit (including SIGTERM 143 / SIGKILL 137) means the helper did not
        // run the AVAssetWriter finalize path — the .mov is truncated or missing entirely.
        // The helper installs a SIGTERM handler BEFORE writing READY to stdout, and start()
        // only returns once READY is received, so by the time the JVM-side stop() can call
        // process.destroy() the SIGTERM handler is guaranteed to be in place — SIGTERM goes
        // through the same finalize path as `q` on stdin and the helper exits 0. If we DO
        // see exit 143 here, the handler didn't run (signal arrived before installation, or
        // the dispatch source was somehow torn down) and the file IS truncated. 137
        // (SIGKILL) is unconditionally forced termination — there's no handler we could
        // install for it.
        //
        // We deliberately do NOT exempt 137/143 as "expected because we sent the signal
        // ourselves". Doing so would silently swallow real truncation events; surfacing the
        // exit lets callers see that the recording's output is unsafe to use. (FfmpegRecorder
        // has a sentSignalOurselves exemption because ffmpeg has no graceful SIGTERM handler;
        // SCK does, so the exemption isn't appropriate here.)
        throw IllegalStateException(
            "spectre-screencapture exited with code $exit during recording — output at $output " +
                "may be truncated or missing."
        )
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS: Long = 5
        const val FORCE_KILL_SECONDS: Long = 2
    }
}

// File-level private constants for `ScreenCaptureKitRecorder`'s `internal companion object`.
// `const val` inside any companion — even an `internal` one — is emitted by the Kotlin compiler
// as a `public static final` JVM field on the outer class and would otherwise leak into the
// committed ABI baseline. The constants below stay private to this file and out of the public
// surface. Same pattern as `STORE_KEY` in `testing/.../ComposeAutomatorExtension.kt`.

// Helper CLI exit codes — kept in lockstep with main.swift's `CLIError`.
private const val HELPER_EXIT_BAD_ARGS: Int = 2
private const val HELPER_EXIT_WINDOW_NOT_FOUND: Int = 3
private const val HELPER_EXIT_TCC_DENIED: Int = 4
private const val HELPER_EXIT_PIPELINE: Int = 5

// Discovery timeout the helper uses internally when scanning for the target window before
// timing out with exit code 3. Callers can override via the recorder's own configuration in
// the future; the default here balances giving the window time to actually appear on screen
// against surfacing genuine "wrong title" mistakes quickly.
private const val DEFAULT_DISCOVERY_TIMEOUT_MS: Int = 2000

// Upper bound on how long start() will wait for the helper to either signal READY on stdout
// or exit with one of the documented fast-fail codes. Must be greater than the helper's own
// [DEFAULT_DISCOVERY_TIMEOUT_MS] plus a margin for SCK init, otherwise start() can return
// before the helper has finished window discovery and the user would only learn of a
// window-not-found failure when stop() throws.
private const val READY_WAIT_MILLIS: Long = (DEFAULT_DISCOVERY_TIMEOUT_MS + 1500).toLong()

// Single-line marker the helper writes to stdout once SCK + AVAssetWriter are running.
// start() blocks until either this line appears (success) or the helper exits (failure
// surfaced via the helper's exit code).
private const val READY_MARKER: String = "READY"

// How often the ready-wait loop polls the atomic flag + process aliveness. Small enough to
// surface failures quickly, large enough that we don't burn CPU spinning.
private const val POLL_INTERVAL_MILLIS: Long = 50

// Brief join budget for the reader thread when the helper process has exited but the reader
// may still be mid-read of buffered bytes. Without this, a helper that writes READY
// immediately followed by a clean exit can be misreported as a startup failure.
private const val READER_DRAIN_MILLIS: Long = 100

// Bounded wait after `destroyForcibly()` in the start-failure path. Unbounded `waitFor()`
// would pin the calling thread if the kernel hasn't reaped the helper yet; a small budget
// bounds the worst case.
private const val FORCE_KILL_DURING_START_SECONDS: Long = 2

// Sentinel exit code surfaced when the helper is still alive after destroyForcibly() + the
// bounded wait. Distinct from any real helper exit code (1..5).
private const val EXIT_HELPER_NOT_REAPED: Int = -1
