package dev.sebastiano.spectre.recording

import java.awt.Rectangle
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Default [Recorder] backed by a system `ffmpeg` binary.
 *
 * Spawns `ffmpeg` as a subprocess on [start] and returns a [RecordingHandle] that signals the
 * subprocess to flush and exit cleanly when the caller stops the recording. The expected path to
 * the binary is resolved via [resolveFfmpegPath]; callers can override the lookup by passing an
 * explicit [ffmpegPath] (useful in tests and for non-PATH installs).
 *
 * Platform support (v1, per the spike plan):
 * - macOS: avfoundation device with region cropping. `RecordingOptions.captureCursor` controls
 *   whether the cursor is baked into frames. **Requires macOS Screen Recording permission for the
 *   JVM process** (see [MacOsRecordingPermissions]).
 * - Windows / Linux: explicitly out of scope for v1; deferred to v3 / v4.
 *
 * Window-targeted capture (`windowHandle != 0`) — i.e. ScreenCaptureKit on macOS — is deferred to
 * v2 (#18). Embedded ComposePanel surfaces with `windowHandle == 0L` always fall through to region
 * capture, which is what this recorder does.
 *
 * The [processFactory] seam exists so the lifecycle can be unit-tested without spawning a real
 * `ffmpeg` (a fake factory can return a `Process`-like stand-in driven by an in-memory pipe).
 */
class FfmpegRecorder(
    private val ffmpegPath: Path = resolveFfmpegPath(),
    private val processFactory: ProcessFactory = SystemProcessFactory,
) : Recorder {

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
        val argv = FfmpegCli.avfoundationRegionCapture(ffmpegPath, region, output, options)
        Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())
        val process = processFactory.start(argv)
        // Fail fast: ffmpeg dies almost instantly on common configuration errors (missing
        // Screen Recording permission, invalid codec, unavailable avfoundation device). The
        // Recorder.start contract promises frames are landing by return, so a process that
        // has already exited must surface as an error rather than a "success" handle that
        // later produces nothing. Interruption during the probe must also clean up the
        // spawned ffmpeg before propagating, otherwise we leak an orphan subprocess.
        val exitedDuringProbe =
            try {
                process.waitFor(STARTUP_PROBE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (e: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                throw e
            }
        if (exitedDuringProbe) {
            throw IllegalStateException(
                "ffmpeg exited immediately (code ${process.exitValue()}) — recording did not start. " +
                    "Common causes: missing Screen Recording permission, invalid codec, or " +
                    "unavailable avfoundation device. Argv: $argv"
            )
        }
        return FfmpegRecordingHandle(process, output)
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    private object SystemProcessFactory : ProcessFactory {
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

    private val stopped = AtomicBoolean(false)

    override val isStopped: Boolean
        get() = stopped.get()

    override fun stop() {
        if (!stopped.compareAndSet(false, true)) return
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
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
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
        // ffmpeg returns 255 on SIGTERM (destroy()) and 137 on SIGKILL (destroyForcibly() —
        // the standard 128+9 Unix convention). Both are signals stop() may have sent itself
        // along the SIGTERM/SIGKILL fallback path, so neither is a crash from the caller's
        // perspective.
        if (exit != 0 && exit != FFMPEG_SIGTERM_EXIT && exit != FFMPEG_SIGKILL_EXIT) {
            throw IllegalStateException(
                "ffmpeg exited with code $exit during recording — output at $output may be " +
                    "truncated or missing. Common causes: disk full, encoder error, device " +
                    "failure during capture."
            )
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS: Long = 5
        const val FORCE_KILL_SECONDS: Long = 2
        const val FFMPEG_SIGTERM_EXIT: Int = 255
        const val FFMPEG_SIGKILL_EXIT: Int = 137 // 128 + 9 (SIGKILL) per POSIX convention
    }
}

private const val STARTUP_PROBE_MILLIS: Long = 200
