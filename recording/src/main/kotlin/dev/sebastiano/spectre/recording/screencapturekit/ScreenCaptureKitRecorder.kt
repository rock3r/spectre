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

        // Startup probe — mirrors FfmpegRecorder. The helper exits within milliseconds for the
        // documented fast-fail codes, so we wait briefly to map them into typed JVM errors
        // rather than handing back a "successful" handle that immediately produces nothing.
        val exitedDuringProbe =
            try {
                process.waitFor(STARTUP_PROBE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                discriminator.restore()
                Thread.currentThread().interrupt()
                throw e
            }
        if (exitedDuringProbe) {
            val exit = process.exitValue()
            discriminator.restore()
            throw IllegalStateException(messageForHelperExit(exit, output, argv))
        }

        return ScreenCaptureKitRecordingHandle(
            process = process,
            output = output,
            discriminator = discriminator,
        )
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    internal object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
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

        // Wide enough that the helper can complete window discovery + SCK init for typical
        // workloads, narrow enough that a fast-fail surfaces synchronously from start().
        const val STARTUP_PROBE_MILLIS: Long = 750
        const val DEFAULT_DISCOVERY_TIMEOUT_MS: Int = 2000

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
    private var sentSignalOurselves: Boolean = false

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
            // suffix on the user's window after a recording attempt would be a bad UX.
            discriminator.restore()
            finished.countDown()
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
            sentSignalOurselves = true
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
        val isExpectedSelfSignal =
            sentSignalOurselves && (exit == POSIX_SIGTERM_EXIT || exit == POSIX_SIGKILL_EXIT)
        if (!isExpectedSelfSignal) {
            throw IllegalStateException(
                "spectre-screencapture exited with code $exit during recording — output at $output " +
                    "may be truncated or missing."
            )
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS: Long = 5
        const val FORCE_KILL_SECONDS: Long = 2
        const val POSIX_SIGTERM_EXIT: Int = 143 // 128 + 15 (SIGTERM)
        const val POSIX_SIGKILL_EXIT: Int = 137 // 128 + 9 (SIGKILL)
    }
}
