package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Default [Recorder] backed by a system `ffmpeg` binary.
 *
 * Spawns `ffmpeg` as a subprocess on [start] and returns a [RecordingHandle] that signals the
 * subprocess to flush and exit cleanly when the caller stops the recording. The expected path to
 * the binary is resolved via [resolveFfmpegPath]; callers can override the lookup by passing an
 * explicit [ffmpegPath] (useful in tests and for non-PATH installs).
 *
 * Platform support:
 * - macOS: avfoundation device with crop-filter region selection. `RecordingOptions.captureCursor`
 *   controls whether the cursor is baked into frames. **Requires macOS Screen Recording permission
 *   for the JVM process** (see [MacOsRecordingPermissions]).
 * - Windows: gdigrab device with input-side region selection (`-offset_x`/`-offset_y`/
 *   `-video_size`). No equivalent TCC permission gate, but gdigrab can't capture minimised windows.
 * - Linux / BSD: not yet implemented — [FfmpegBackend.detect] throws on those hosts. Tracked under
 *   v4.
 *
 * The platform backend is picked at construction time via [FfmpegBackend.detect] and can be
 * overridden via the internal constructor for tests (so the produced argv is deterministic
 * regardless of the host OS).
 *
 * Window-targeted capture (`windowHandle != 0`) — i.e. ScreenCaptureKit on macOS — is handled by a
 * separate `WindowRecorder` and routed by [AutoRecorder]. Embedded ComposePanel surfaces with
 * `windowHandle == 0L` always fall through to region capture, which is what this recorder does.
 *
 * The [processFactory] seam exists so the lifecycle can be unit-tested without spawning a real
 * `ffmpeg` (a fake factory can return a `Process`-like stand-in driven by an in-memory pipe).
 */
class FfmpegRecorder
internal constructor(
    private val ffmpegPath: Path,
    private val processFactory: ProcessFactory,
    // Provider — resolved on first [start] call rather than at construction time. Eagerly
    // calling `FfmpegBackend.detect()` from the public constructor would make `FfmpegRecorder()`
    // (and `AutoRecorder()`'s default ffmpegRecorder) throw immediately on Linux/BSD even when
    // recording is never attempted, breaking call sites that instantiate recorders during app
    // startup. Deferring keeps construction OS-agnostic and surfaces the unsupported-host error
    // only at the point of use.
    private val backendProvider: () -> FfmpegBackend,
) : Recorder {

    constructor(
        ffmpegPath: Path = resolveFfmpegPath(),
        processFactory: ProcessFactory = SystemProcessFactory,
    ) : this(ffmpegPath, processFactory, FfmpegBackend::detect)

    init {
        require(Files.isExecutable(ffmpegPath) || ffmpegPath == PROBE_PATH) {
            "ffmpeg binary not executable at $ffmpegPath"
        }
    }

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val argv = backendProvider().buildRegionArgv(ffmpegPath, region, output, options)
        return spawnFfmpegRecording(processFactory, argv, output)
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    internal object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            ProcessBuilder(argv)
                // Discard ffmpeg's stdout and stderr. We never consume them, and leaving them
                // on the default pipe means a noisy ffmpeg run can fill the pipe buffer and
                // deadlock the encoder. DISCARD routes the streams to /dev/null at the OS
                // level so ffmpeg can write freely without backpressure.
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                // ffmpeg listens for `q` on stdin to flush + exit cleanly. We need PIPE so we
                // can send it from RecordingHandle.stop().
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
    }

    companion object {

        // Sentinel returned by resolveFfmpegPath() when no binary is found. Constructing an
        // FfmpegRecorder against this path lets the caller see the explicit failure message
        // immediately rather than at start() time, but prevents the strict isExecutable check
        // from blocking the no-binary path used in tests / probing.
        internal val PROBE_PATH: Path = Paths.get("ffmpeg")

        /**
         * Builds an [FfmpegRecorder] bound to a specific [backend], using the system process
         * factory and an auto-resolved ffmpeg path. Internal so tests / smokes can pin the backend
         * without depending on the host OS — callers outside the module use the public constructor
         * whose backend is detected automatically.
         */
        internal fun withBackend(
            backend: FfmpegBackend,
            ffmpegPath: Path = resolveFfmpegPath(),
        ): FfmpegRecorder = FfmpegRecorder(ffmpegPath, SystemProcessFactory, { backend })

        /**
         * Best-effort lookup for a `ffmpeg` binary on PATH. Returns the resolved absolute path if
         * found, or [PROBE_PATH] (the unresolved name) if not — callers that try to instantiate
         * [FfmpegRecorder] with [PROBE_PATH] will get a clear error explaining the missing binary
         * at use time.
         */
        fun resolveFfmpegPath(): Path = which("ffmpeg") ?: PROBE_PATH

        private fun which(executable: String): Path? {
            val pathEnv = System.getenv("PATH") ?: return null
            val separator = System.getProperty("path.separator") ?: ":"
            for (dir in pathEnv.split(separator)) {
                if (dir.isBlank()) continue
                val candidate = Paths.get(dir, executable)
                if (Files.isExecutable(candidate)) return candidate.toAbsolutePath()
            }
            return null
        }
    }
}

private class FfmpegRecordingHandle(private val process: Process, override val output: Path) :
    RecordingHandle {

    // Three-state lifecycle so concurrent observers see an accurate `isStopped`:
    //   - stopInitiated: CAS guard so the cleanup runs exactly once.
    //   - finished:      CountDownLatch that releases waiters once the first cleanup attempt
    //                    completes (success or failure).
    //   - result:        Result<Unit> set by the first cleanup. isStopped reflects this — it
    //                    is true only on success. Concurrent callers replay the same failure
    //                    so a leaked-orphan situation is observable to every caller, not just
    //                    the first.
    private val stopInitiated = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val result = AtomicReference<Result<Unit>?>()

    override val isStopped: Boolean
        get() = result.get()?.isSuccess == true

    // Tracks whether stop() *itself* sent SIGTERM/SIGKILL to ffmpeg. Used to decide whether a
    // signal-shaped exit code (255 / 137 / 143) is "ours" (expected, not a crash) or external
    // (CI/test harness/OS killed ffmpeg, which IS an error from the caller's perspective).
    private var sentSignalOurselves: Boolean = false

    override fun stop() {
        if (!stopInitiated.compareAndSet(false, true)) {
            // Another thread is already running the cleanup. Block until it finishes, then
            // replay the same outcome — including any exception — so concurrent callers do
            // not silently observe a "stopped" state that the first call actually failed to
            // achieve.
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
            finished.countDown()
        }
    }

    private fun stopInternal() {
        if (!process.isAlive) {
            // ffmpeg already exited — could be a clean prior stop, or a mid-recording crash
            // (disk full, encoder error, device failure). The handle is the only error
            // boundary callers see, so a non-zero exit must surface as an error rather than
            // letting the caller trust a truncated or missing output file.
            failIfFfmpegCrashed()
            return
        }
        // Send 'q' on stdin: ffmpeg's documented clean-shutdown signal. The subprocess flushes
        // its mux buffer, finalises the output file, and exits. SIGTERM / SIGKILL as fallback
        // for stalled processes.
        @Suppress("TooGenericExceptionCaught")
        try {
            process.outputStream?.use { it.write('q'.code) }
        } catch (_: IOException) {
            // ffmpeg may have already exited; the waitFor below confirms.
        } catch (t: Throwable) {
            // Any failure here still goes to the SIGTERM fallback.
            if (t is InterruptedException) Thread.currentThread().interrupt()
        }
        // Catch InterruptedException around every waitFor so cancellation never short-circuits
        // the SIGTERM/SIGKILL fallback path. We still call destroyForcibly() in that case so
        // ffmpeg can't outlive us, then re-set the interrupt flag for the caller to observe.
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
                // destroyForcibly() is asynchronous on the JVM. Wait once more so the post-
                // stop world really has the process gone (otherwise isStopped=true could be
                // observable while ffmpeg is still finalising the output file).
                try {
                    process.waitFor(FORCE_KILL_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (process.isAlive) {
            // Even after destroyForcibly(), ffmpeg refused to die within the wait window.
            // Surface this as a hard failure rather than letting the caller observe
            // isStopped=true while the process is still mutating the output file.
            throw IllegalStateException(
                "ffmpeg did not exit after destroyForcibly() within ${FORCE_KILL_SECONDS}s — " +
                    "output at $output is in an undefined state."
            )
        }
        failIfFfmpegCrashed()
    }

    private fun failIfFfmpegCrashed() {
        // exitValue throws IllegalThreadStateException if the process is still alive; we only
        // call this from paths that have already established the process exited.
        val exit =
            @Suppress("TooGenericExceptionCaught")
            try {
                process.exitValue()
            } catch (_: Throwable) {
                return
            }
        if (exit == 0) return
        // 255 (ffmpeg-on-SIGTERM), 143 (POSIX 128+15 SIGTERM if ffmpeg's signal handler
        // didn't run), and 137 (POSIX 128+9 SIGKILL) are *only* exempted when stop() sent
        // the signal itself — see sentSignalOurselves. Otherwise they signify external
        // termination (CI / test harness / OS killing ffmpeg) which IS a crash from the
        // caller's perspective: the recording was interrupted and the output is likely
        // truncated.
        val isExpectedSelfSignal =
            sentSignalOurselves &&
                (exit == FFMPEG_SIGTERM_EXIT ||
                    exit == POSIX_SIGTERM_EXIT ||
                    exit == FFMPEG_SIGKILL_EXIT)
        if (!isExpectedSelfSignal) {
            throw IllegalStateException(
                "ffmpeg exited with code $exit during recording — output at $output may be " +
                    "truncated or missing. Common causes: disk full, encoder error, device " +
                    "failure during capture, or external termination."
            )
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS: Long = 5
        const val FORCE_KILL_SECONDS: Long = 2
        const val FFMPEG_SIGTERM_EXIT: Int = 255
        const val POSIX_SIGTERM_EXIT: Int = 143 // 128 + 15 (SIGTERM)
        const val FFMPEG_SIGKILL_EXIT: Int = 137 // 128 + 9 (SIGKILL)
    }
}

/**
 * Spawn an `ffmpeg` subprocess against [argv], block briefly to detect immediate exits, and wrap
 * the live process in a [RecordingHandle]. Shared by [FfmpegRecorder] (region capture) and
 * [FfmpegWindowRecorder] (Windows title-targeted capture) so the startup probe + signal-handling
 * + crash-detection lifecycle stays in one place.
 *
 * The startup probe is failure-mode-agnostic: it just observes that the spawned process is still
 * alive after a short window. Common immediate-exit triggers across the two recorder shapes:
 * - missing macOS Screen Recording permission (avfoundation device fails to open),
 * - invalid codec / unavailable device,
 * - gdigrab `title=` that matches no visible window,
 * - output path on a read-only filesystem.
 */
internal fun spawnFfmpegRecording(
    processFactory: FfmpegRecorder.ProcessFactory,
    argv: List<String>,
    output: Path,
): RecordingHandle {
    Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())
    val process = processFactory.start(argv)
    val exitedDuringProbe =
        try {
            process.waitFor(STARTUP_PROBE_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            process.destroyForcibly()
            // destroyForcibly() is asynchronous — wait once more so the orphan ffmpeg really
            // has gone before we propagate the interrupt to the caller. We explicitly do NOT
            // catch InterruptedException here: another interrupt during this wait means the JVM
            // is being torn down anyway, and the daemon semantics of subprocesses will let it
            // die with the JVM.
            @Suppress("TooGenericExceptionCaught", "SwallowedException")
            val terminated =
                try {
                    process.waitFor(FORCE_KILL_DURING_START_SECONDS, TimeUnit.SECONDS)
                } catch (_: Throwable) {
                    false
                }
            Thread.currentThread().interrupt()
            if (!terminated) {
                throw IllegalStateException(
                    "ffmpeg refused to die after destroyForcibly() during start() interrupt — " +
                        "leaking an orphan recorder is not safe; output at $output is in an " +
                        "undefined state."
                )
            }
            throw e
        }
    if (exitedDuringProbe) {
        throw IllegalStateException(
            "ffmpeg exited immediately (code ${process.exitValue()}) — recording did not start. " +
                "Common causes: missing macOS Screen Recording permission, invalid codec, " +
                "unavailable avfoundation/gdigrab device, or a Windows gdigrab `title=` that " +
                "matches no visible window. Argv: $argv"
        )
    }
    return FfmpegRecordingHandle(process, output)
}

private const val STARTUP_PROBE_MILLIS: Long = 200
private const val FORCE_KILL_DURING_START_SECONDS: Long = 2
