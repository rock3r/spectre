package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.GstCli
import dev.sebastiano.spectre.recording.Recorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
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
 * [Recorder] for Linux Wayland sessions, backed by `xdg-desktop-portal` (via [ScreenCastPortal])
 * for permission + PipeWire stream selection, and `gst-launch-1.0` for the actual encoding.
 *
 * Counterpart to [dev.sebastiano.spectre.recording.FfmpegRecorder] but architecturally distinct:
 *
 * 1. **D-Bus session held open across the recording.** The portal binds the screen-cast permission
 *    grant to the calling D-Bus connection. If we closed the connection right after the
 *    Start.Response (the way a stateless argv builder would), the compositor would tear the
 *    PipeWire stream down and gst-launch would record empty buffers. So [PortalSession] holds the
 *    connection live until the [RecordingHandle] is stopped.
 *
 * 2. **gst-launch, not ffmpeg.** Stock Ubuntu 22.04 ships GStreamer 1.20 with a working
 *    `pipewiresrc`; ffmpeg's `pipewiregrab` device only landed in 6.1 and isn't available in the
 *    distro. Spectre's supported floor stays at "what Ubuntu 22.04 / Debian 12 / RHEL 9 ship by
 *    default." See [GstCli.pipewireRegionCapture] for the argv shape.
 *
 * 3. **First call pops a permission dialog.** [PortalSession] documents this; for interactive dev
 *    iteration it's a one-time click, for automated tests in CI we use Xvfb (covered by
 *    `validation-linux.yml` from #79) which sidesteps the portal entirely.
 *
 * The class is module-internal: callers go through [dev.sebastiano.spectre.recording.AutoRecorder]
 * or directly construct [WaylandPortalRecorder] from inside the recording module / its tests.
 */
internal class WaylandPortalRecorder(
    private val portal: ScreenCastPortal = ScreenCastPortal(),
    private val gstLaunchPath: Path = resolveGstLaunchPath(),
    private val processFactory: ProcessFactory = SystemProcessFactory,
    private val sourceTypes: Set<SourceType> = setOf(SourceType.MONITOR),
) : Recorder {

    init {
        require(Files.isExecutable(gstLaunchPath) || gstLaunchPath == PROBE_PATH) {
            "gst-launch-1.0 binary not executable at $gstLaunchPath. Install via " +
                "`sudo apt install gstreamer1.0-tools gstreamer1.0-plugins-good " +
                "gstreamer1.0-plugins-bad gstreamer1.0-plugins-ugly` on Debian/Ubuntu."
        }
    }

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val session =
            portal.openSession(
                sourceTypes = sourceTypes,
                cursorMode = if (options.captureCursor) CursorMode.EMBEDDED else CursorMode.HIDDEN,
            )
        try {
            // Translate the requested region into the PipeWire stream's coordinate space.
            // `position` is the stream's top-left in monitor coords; for a single-monitor
            // capture this is usually (0,0) but multi-monitor or virtual-display setups can
            // shift it. The user passes `region` in AWT screen coords; we subtract the stream
            // position so the videocrop pixel insets are correct.
            val streamPosition = session.position
            val streamRelativeRegion =
                Rectangle(
                    region.x - streamPosition.first,
                    region.y - streamPosition.second,
                    region.width,
                    region.height,
                )
            val argv =
                GstCli.pipewireRegionCapture(
                    gstLaunchPath = gstLaunchPath,
                    pipewireNodeId = session.nodeId,
                    region = streamRelativeRegion,
                    streamSize = session.size,
                    output = output,
                    options = options,
                )
            // Print the resolved argv to stderr so the manual smoke (which `tee`s the JVM's
            // streams into a log file) captures the exact gst-launch invocation. Keeps the
            // recorder silent in production paths because System.err is unconfigured —
            // callers that route stderr to /dev/null see nothing; callers that route it to
            // a file see the diagnostic.
            System.err.println("[WaylandPortalRecorder] argv: $argv")
            System.err.println(
                "[WaylandPortalRecorder] portal stream node=${session.nodeId}, " +
                    "size=${session.size}, position=${session.position}, " +
                    "stream-relative region=$streamRelativeRegion"
            )
            Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())
            val process = processFactory.start(argv)
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
                    "gst-launch-1.0 exited immediately (code ${process.exitValue()}) — " +
                        "recording did not start. Common causes: missing pipewiresrc / x264enc " +
                        "/ mp4mux plugin, PipeWire daemon not reachable, output path on a " +
                        "read-only filesystem. Argv: $argv"
                )
            }
            return GstRecordingHandle(process, session, output)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            // Anything that escapes after the portal session opened MUST close the session,
            // otherwise the compositor leaves a "Spectre is sharing your screen" indicator
            // hanging and the user has to manually revoke it.
            runCatching { session.close() }
            throw t
        }
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    internal object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process {
            // gst-launch can produce a wall of diagnostic output when verbose. We need the
            // diagnostic on failures (otherwise a 0-byte recording is silent corruption) but
            // don't want it noising up healthy runs. Compromise: route both stdout and stderr
            // to a per-PID log file under the JVM's tmpdir. Smoke / debug callers can `cat`
            // the file; production callers can read it on failure or ignore it on success.
            // Compared to a PIPE-with-reader-thread design, this trades "lose chatter past
            // the pipe buffer" for "pay the IO cost of a tmpfile" — and tmpfs makes the IO
            // free on Linux runners.
            val logDir = Path.of(System.getProperty("java.io.tmpdir"), "spectre-gst-launch-logs")
            Files.createDirectories(logDir)
            val logFile = logDir.resolve("gst-launch-${System.currentTimeMillis()}.log").toFile()
            return ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
                .redirectError(ProcessBuilder.Redirect.appendTo(logFile))
                // gst-launch uses SIGTERM (with `-e` set) to mean "send EOS through the
                // pipeline and finalise." No stdin protocol; we don't need a pipe here, but
                // keeping it as PIPE matches FfmpegRecorder.SystemProcessFactory's shape.
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
                .also {
                    // Print the log path so callers (especially the smoke) know where to look
                    // when debugging a 0-byte recording. The recorder's wider lifecycle is
                    // silent on stderr; this one line is intentional.
                    System.err.println("[WaylandPortalRecorder] gst-launch log: $logFile")
                }
        }
    }

    companion object {

        // Sentinel returned by resolveGstLaunchPath() when no binary is found. Constructing a
        // WaylandPortalRecorder against this path gives the caller a clear error at start()
        // time rather than at construction.
        internal val PROBE_PATH: Path = Paths.get("gst-launch-1.0")

        fun resolveGstLaunchPath(): Path = which("gst-launch-1.0") ?: PROBE_PATH

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

/**
 * Recording handle for the gst-launch + portal-session pair. Closes the gst-launch subprocess via
 * SIGTERM (which gst-launch's `-e` flag turns into a clean EOS through the pipeline), waits for the
 * file to finalise, then closes the portal session — in that order. Reversing the order would tear
 * the PipeWire stream down before gst-launch could drain its buffer, leaving an unfinalised mp4.
 *
 * Mirrors `FfmpegRecorder.FfmpegRecordingHandle`'s lifecycle invariants — see that class for the
 * three-state CAS / latch / result design that lets concurrent `stop()` callers all see the same
 * outcome.
 */
private class GstRecordingHandle(
    private val process: Process,
    private val session: PortalSession,
    override val output: Path,
) : RecordingHandle {

    private val stopInitiated = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val result = AtomicReference<Result<Unit>?>()

    override val isStopped: Boolean
        get() = result.get()?.isSuccess == true

    private var sentSignalOurselves: Boolean = false

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
            // Close the portal session AFTER gst-launch's exit (guaranteed by stopInternal's
            // wait). If we close the session while gst-launch is still flushing, the PipeWire
            // stream gets torn down mid-finalisation and we lose the trailing frames + the
            // moov atom.
            runCatching { session.close() }
            finished.countDown()
        }
    }

    private fun stopInternal() {
        if (!process.isAlive) {
            failIfGstCrashed()
            return
        }
        // gst-launch with `-e` interprets SIGTERM as End-Of-Stream → pipeline drains → mux
        // finalises → exit 0. There's no stdin protocol to send like ffmpeg's `q`.
        sentSignalOurselves = true
        process.destroy()
        var interrupted = false
        val gracefulExit =
            try {
                process.waitFor(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
        if (!gracefulExit) {
            process.destroyForcibly()
            try {
                process.waitFor(FORCE_KILL_SECONDS, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (process.isAlive) {
            throw IllegalStateException(
                "gst-launch did not exit after destroyForcibly() within ${FORCE_KILL_SECONDS}s — " +
                    "output at $output is in an undefined state."
            )
        }
        failIfGstCrashed()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun failIfGstCrashed() {
        val exit =
            try {
                process.exitValue()
            } catch (_: Throwable) {
                return
            }
        if (exit == 0) return
        // SIGTERM-shaped exits (143 = 128+15) are expected ONLY when stop() sent the signal
        // itself. Any other non-zero exit means gst-launch died for a real reason — bad
        // pipeline, plugin missing at runtime, encoder error, disk full — and the output is
        // truncated.
        val isExpectedSelfSignal = sentSignalOurselves && (exit == POSIX_SIGTERM_EXIT || exit == 0)
        if (!isExpectedSelfSignal) {
            throw IllegalStateException(
                "gst-launch exited with code $exit during recording — output at $output may be " +
                    "truncated or missing. Common causes: PipeWire daemon disconnect, encoder " +
                    "error, disk full, external termination."
            )
        }
    }

    @Suppress("UnusedPrivateMember") private fun unused() = IOException("placeholder")

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS: Long = 5
        const val FORCE_KILL_SECONDS: Long = 2
        const val POSIX_SIGTERM_EXIT: Int = 143
    }
}

private const val STARTUP_PROBE_MILLIS: Long = 500
