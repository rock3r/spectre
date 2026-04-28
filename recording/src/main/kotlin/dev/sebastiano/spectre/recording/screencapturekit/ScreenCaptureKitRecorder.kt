package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.io.IOException
import java.lang.ProcessHandle
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
 * Why not implement [dev.sebastiano.spectre.recording.Recorder]: that interface is region-based
 * (`Rectangle`), and ScreenCaptureKit is window-targeted. A higher-level router can pick between
 * `ScreenCaptureKitRecorder` (when a `windowHandle != 0L` is available) and `FfmpegRecorder`
 * (region fallback for embedded `ComposePanel`s) — that lives outside this class.
 *
 * macOS-only. On non-macOS hosts the helper isn't bundled; [start] fails early with a clear "helper
 * not found" error from [HelperBinaryExtractor].
 */
class ScreenCaptureKitRecorder
internal constructor(
    private val helperExtractor: HelperBinaryExtractor,
    private val processFactory: ProcessFactory,
) {

    constructor() : this(HelperBinaryExtractor(), SystemProcessFactory)

    fun start(
        window: TitledWindow,
        windowOwnerPid: Long = ProcessHandle.current().pid(),
        output: Path,
        options: RecordingOptions = RecordingOptions(),
    ): RecordingHandle {
        // Resolve everything that can fail without touching window state first. Anything we do
        // before `discriminator.apply()` doesn't need a restore path; anything after must
        // restore on every error branch (the original window title is held by the
        // discriminator and would otherwise stay dirty across a failed start).
        val helperPath = helperExtractor.extract()
        Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())

        val discriminator = TitleDiscriminator(window)
        discriminator.apply()

        // HelperArguments.init validates pid/title/fps/timeout — IllegalArgumentException is
        // the only failure mode here. Anything else escaping from the constructor would be a
        // coding bug we want surfaced loudly, not swallowed-and-restored.
        val argv =
            try {
                HelperArguments(
                        pid = windowOwnerPid,
                        titleContains = discriminator.value,
                        output = output,
                        fps = options.frameRate,
                        captureCursor = options.captureCursor,
                        discoveryTimeoutMs = DEFAULT_DISCOVERY_TIMEOUT_MS,
                    )
                    .toArgv(helperPath)
            } catch (e: IllegalArgumentException) {
                discriminator.restore()
                throw e
            }

        // ProcessBuilder.start throws IOException for every failure mode (missing binary,
        // permission denied on exec, fork failure). Catch that specifically so a non-IO bug in
        // a test ProcessFactory propagates loudly instead of triggering a silent restore path.
        val process =
            try {
                processFactory.start(argv)
            } catch (e: IOException) {
                discriminator.restore()
                throw e
            }

        // Wait for the helper to either:
        //   - emit the READY marker on stdout (window discovered, SCK is streaming frames), or
        //   - exit with one of the documented fast-fail codes (2/3/4/5).
        // A plain `process.waitFor(STARTUP_PROBE_MILLIS)`-style probe was racy: when the
        // helper's window discovery took longer than the probe, start() reported success
        // before the helper had actually started capturing. Reading stdout removes the race.
        val ready =
            try {
                awaitHelperReady(process, READY_WAIT_MILLIS)
            } catch (e: InterruptedException) {
                // destroyForcibly() is asynchronous on the JVM. Without a bounded wait here we
                // can return from start() while the helper is still alive + writing the
                // output file — leaks a subprocess and leaves the .mov mutating after the
                // caller thinks startup aborted. Mirror the non-ready path's bounded wait so
                // the helper is genuinely gone by the time we propagate the interrupt.
                process.destroyForcibly()
                @Suppress("SwallowedException")
                try {
                    process.waitFor(FORCE_KILL_DURING_START_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // A second interrupt during cleanup means the JVM is being torn down
                    // anyway — daemon-process semantics will let the helper die with us.
                }
                discriminator.restore()
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
                    // the timeout, fall back to a sentinel exit code so we don't propagate a
                    // hang into the caller.
                    if (process.waitFor(FORCE_KILL_DURING_START_SECONDS, TimeUnit.SECONDS)) {
                        process.exitValue()
                    } else {
                        EXIT_HELPER_NOT_REAPED
                    }
                } else {
                    process.exitValue()
                }
            discriminator.restore()
            throw IllegalStateException(messageForHelperExit(exit, output, argv))
        }

        return ScreenCaptureKitRecordingHandle(
            process = process,
            output = output,
            discriminator = discriminator,
        )
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
            if (!process.isAlive) return false
            Thread.sleep(POLL_INTERVAL_MILLIS)
        }
        // Timeout — best-effort to interrupt the reader so it doesn't outlive the recorder.
        reader.interrupt()
        return ready.get()
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    interface ProcessFactory {
        fun start(argv: List<String>): Process
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
        // Helper CLI exit codes — kept in lockstep with main.swift's `CLIError`.
        const val HELPER_EXIT_BAD_ARGS: Int = 2
        const val HELPER_EXIT_WINDOW_NOT_FOUND: Int = 3
        const val HELPER_EXIT_TCC_DENIED: Int = 4
        const val HELPER_EXIT_PIPELINE: Int = 5

        // Discovery timeout that the helper uses internally when scanning for the target
        // window before timing out with exit code 3. Callers can override via the recorder's
        // own configuration in the future; the default here balances giving the window time
        // to actually appear on screen against surfacing genuine "wrong title" mistakes
        // quickly.
        const val DEFAULT_DISCOVERY_TIMEOUT_MS: Int = 2000

        // Upper bound on how long start() will wait for the helper to either signal READY on
        // stdout or exit with one of the documented fast-fail codes. Must be greater than the
        // helper's own [DEFAULT_DISCOVERY_TIMEOUT_MS] plus a margin for SCK init, otherwise
        // start() can return before the helper has finished window discovery and the user
        // would only learn of a window-not-found failure when stop() throws.
        const val READY_WAIT_MILLIS: Long = (DEFAULT_DISCOVERY_TIMEOUT_MS + 1500).toLong()

        // Single-line marker the helper writes to stdout once SCK + AVAssetWriter are
        // running. start() blocks until either this line appears (success) or the helper
        // exits (failure surfaced via the helper's exit code).
        const val READY_MARKER: String = "READY"

        // How often the ready-wait loop polls the atomic flag + process aliveness. Small
        // enough to surface failures quickly, large enough that we don't burn CPU spinning.
        const val POLL_INTERVAL_MILLIS: Long = 50

        // Bounded wait after `destroyForcibly()` in the start-failure path. Unbounded
        // `waitFor()` would pin the calling thread if the kernel hasn't reaped the helper
        // yet; a small budget bounds the worst case.
        const val FORCE_KILL_DURING_START_SECONDS: Long = 2

        // Sentinel exit code surfaced when the helper is still alive after destroyForcibly()
        // + the bounded wait. Distinct from any real helper exit code (1..5).
        const val EXIT_HELPER_NOT_REAPED: Int = -1

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
        @Suppress("TooGenericExceptionCaught")
        try {
            stopInternal()
            result.set(Result.success(Unit))
        } catch (t: Throwable) {
            result.set(Result.failure(t))
            throw t
        } finally {
            // Restore the title even when the helper crash branch threw — leaving a Spectre/
            // suffix on the user's window after a recording attempt would be a bad UX. Wrap
            // restore() in its own try/finally so a misbehaving custom TitledWindow can't
            // skip countDown — concurrent threads that lost the stopInitiated CAS race are
            // blocked on `finished.await()` and would deadlock otherwise.
            try {
                discriminator.restore()
            } finally {
                finished.countDown()
            }
        }
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
        // The helper installs a SIGTERM handler that converts SIGTERM into a graceful
        // finalize + exit(0), so seeing 143 here means the handler didn't get to run (e.g.
        // the signal arrived before setup completed). 137 (SIGKILL) is unconditionally a
        // forced termination — there's no handler we could install for it. Either way the
        // file is unsafe to use; surface that to the caller rather than silently swallowing.
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
