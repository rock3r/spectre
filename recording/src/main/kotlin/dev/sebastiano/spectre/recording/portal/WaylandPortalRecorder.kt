package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.Recorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Rectangle
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlinx.serialization.json.Json

/**
 * [Recorder] for Linux Wayland sessions, backed by a small native helper binary
 * (`spectre-wayland-helper`, built from `recording/native/linux/`).
 *
 * The helper handles the parts of the Wayland recording flow the JVM can't reach cleanly:
 *
 * 1. **xdg-desktop-portal handshake** — `CreateSession` → `SelectSources` → `Start` →
 *    `OpenPipeWireRemote`. Returns the PipeWire FD authorising the caller to read the granted
 *    screen-cast node.
 * 2. **FD inheritance into `gst-launch-1.0`** — clears `O_CLOEXEC` on the FD and spawns the encoder
 *    with the FD inherited. The JVM's `ProcessBuilder` doesn't inherit arbitrary FDs across `exec`;
 *    Rust's `Command` does, and `nix::fcntl` makes the CLOEXEC manipulation a one-liner.
 * 3. **Lifecycle** — owns the gst-launch process; SIGTERMs it on stop (with `-e` for clean EOS /
 *    mp4 finalisation); waits for it to exit and reports the output file size.
 *
 * The JVM talks to the helper over stdin/stdout via newline-delimited JSON:
 * - JVM → helper: [Command.Start] (one), [Command.Stop] (one).
 * - Helper → JVM: [Event.Started] (after portal grant + gst-launch spawn), [Event.Stopped] (after
 *   gst-launch exits cleanly), or [Event.Error] (any failure).
 *
 * [start] blocks until the helper emits [Event.Started] (= recording is in flight) or [Event.Error]
 * (= portal rejection, encoder crash on launch, etc.). [RecordingHandle.stop] blocks until
 * [Event.Stopped] arrives (= mp4 finalised) or the helper exits.
 *
 * **Why this shape vs. the JVM-only attempt**: the original stage-2 try used dbus-java for the
 * portal handshake and JNR-POSIX for FD inheritance; the dbus-java side hit a UnixFD-unmarshalling
 * bug on the GNOME mutter portal that wasn't fixable with a trivial edit (#80 comments have the
 * bake-off failure analysis). The Rust helper sidesteps both pain points: dbus-rs handles UnixFDs
 * natively, and Rust's `Command` makes FD inheritance trivial. The helper is ~600 LOC, ships as a
 * per-arch binary in the recording module's jar resources, and uses a tiny stdin/stdout JSON
 * protocol.
 */
internal class WaylandPortalRecorder(
    private val helperExtractor: WaylandHelperBinaryExtractor = WaylandHelperBinaryExtractor(),
    private val processFactory: ProcessFactory = SystemProcessFactory,
    private val sourceTypes: List<SourceType> = listOf(SourceType.MONITOR),
    private val startedTimeout: Long = DEFAULT_STARTED_TIMEOUT_MS,
) : Recorder {

    private val json = Json {
        ignoreUnknownKeys = true
        // Per-class discriminators come from `@JsonClassDiscriminator` on [Command] /
        // [Event] — `command` and `event` respectively. No global override needed here.
    }

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        startInternal(
            sourceTypes = sourceTypes,
            region =
                Region(x = region.x, y = region.y, width = region.width, height = region.height),
            output = output,
            options = options,
        )

    /**
     * Internal entry point shared with [WaylandPortalWindowRecorder]. Pass [region] = null to
     * record the entire PipeWire stream uncropped (required for `SourceType.WINDOW`, see
     * [WaylandHelperProtocol.Command.Start.region] for why).
     */
    @Suppress("LongMethod")
    internal fun startInternal(
        sourceTypes: List<SourceType>,
        region: Region?,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val helperPath = helperExtractor.extract()
        Files.createDirectories(output.toAbsolutePath().parent ?: output.toAbsolutePath())
        val process = processFactory.start(helperPath)

        // The reader thread, the latches, and the events live for the recorder's lifetime.
        // [GstRecordingHandle] takes ownership of all of them once we've confirmed Started.
        val started = AtomicReference<Event.Started?>()
        val stopped = AtomicReference<Event.Stopped?>()
        val errorEvent = AtomicReference<Event.Error?>()
        val startedLatch = CountDownLatch(1)
        val stoppedLatch = CountDownLatch(1)
        val readerThread =
            thread(name = "wayland-helper-reader", isDaemon = true) {
                BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))
                    .use { reader ->
                        readerLoop(reader, started, stopped, errorEvent, startedLatch, stoppedLatch)
                    }
            }

        @Suppress("TooGenericExceptionCaught")
        try {
            writeCommand(
                process.outputStream,
                Command.Start(
                    sourceTypes = sourceTypes,
                    cursorMode =
                        if (options.captureCursor) CursorMode.EMBEDDED else CursorMode.HIDDEN,
                    frameRate = options.frameRate,
                    region = region,
                    output = output.toAbsolutePath().toString(),
                    codec = options.codec,
                ),
            )
        } catch (e: IOException) {
            process.destroyForcibly()
            throw IllegalStateException(
                "Failed to send Start command to spectre-wayland-helper (it died on launch?). " +
                    "Helper path: $helperPath",
                e,
            )
        }

        if (!startedLatch.await(startedTimeout, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException(
                "spectre-wayland-helper did not emit a Started or Error event within " +
                    "${startedTimeout}ms. Likely portal-handshake stall (user dialog hidden, " +
                    "compositor unresponsive, D-Bus session bus not reachable from helper)."
            )
        }
        errorEvent.get()?.let { err ->
            process.destroyForcibly()
            throw IllegalStateException(
                "spectre-wayland-helper reported an error before recording could start: " +
                    "kind=${err.kind} message=${err.message}"
            )
        }
        val startedEvent =
            started.get()
                ?: error(
                    "Started latch fired but neither Started nor Error event captured — " +
                        "synchronisation bug in WaylandPortalRecorder."
                )
        return GstRecordingHandle(
            process = process,
            output = output,
            json = json,
            startedEvent = startedEvent,
            stoppedRef = stopped,
            errorRef = errorEvent,
            stoppedLatch = stoppedLatch,
            readerThread = readerThread,
        )
    }

    @Suppress("LongParameterList", "TooGenericExceptionCaught", "LoopWithTooManyJumpStatements")
    private fun readerLoop(
        reader: BufferedReader,
        started: AtomicReference<Event.Started?>,
        stopped: AtomicReference<Event.Stopped?>,
        errorEvent: AtomicReference<Event.Error?>,
        startedLatch: CountDownLatch,
        stoppedLatch: CountDownLatch,
    ) {
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val event =
                try {
                    json.decodeFromString(Event.serializer(), line)
                } catch (e: Throwable) {
                    errorEvent.compareAndSet(
                        null,
                        Event.Error(
                            kind = "ProtocolDecode",
                            message =
                                "failed to parse helper event line " +
                                    "'${line.take(MALFORMED_LINE_PREVIEW_LENGTH)}': ${e.message}",
                        ),
                    )
                    startedLatch.countDown()
                    stoppedLatch.countDown()
                    return
                }
            when (event) {
                is Event.Started -> {
                    started.set(event)
                    startedLatch.countDown()
                }
                is Event.Stopped -> {
                    stopped.set(event)
                    stoppedLatch.countDown()
                }
                is Event.Error -> {
                    errorEvent.compareAndSet(null, event)
                    startedLatch.countDown()
                    stoppedLatch.countDown()
                }
                is Event.FrameProgress -> {
                    // Reserved for a future progress-streaming UX.
                }
            }
        }
        // EOF on helper stdout. If we never saw a Started event, synthesise an error so the
        // start-side latch unblocks instead of waiting out its timeout.
        if (started.get() == null && errorEvent.get() == null) {
            errorEvent.compareAndSet(
                null,
                Event.Error(
                    kind = "HelperEofBeforeStarted",
                    message =
                        "helper closed stdout without emitting a Started or Error event — " +
                            "usually means it crashed during the portal handshake. Check the " +
                            "helper's stderr (forwarded to this process's stderr).",
                ),
            )
        }
        startedLatch.countDown()
        stoppedLatch.countDown()
    }

    private fun writeCommand(stream: OutputStream, command: Command) {
        val line = json.encodeToString(Command.serializer(), command) + "\n"
        stream.write(line.toByteArray(StandardCharsets.UTF_8))
        stream.flush()
    }

    /** Indirection over `ProcessBuilder.start()` so tests can drive the subprocess lifecycle. */
    interface ProcessFactory {
        fun start(helperPath: Path): Process
    }

    internal object SystemProcessFactory : ProcessFactory {
        override fun start(helperPath: Path): Process =
            ProcessBuilder(helperPath.toString())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                // Helper's stderr (and gst-launch's via inherit-from-helper) goes to this
                // JVM's stderr — readable by smoke tests, makes failure modes visible to
                // production callers without an extra log-file dance.
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
    }

    private companion object {
        const val DEFAULT_STARTED_TIMEOUT_MS: Long = 90_000

        // Cap on how much of a malformed JSON line we echo back into the Error event message.
        // Long enough to be useful for debugging (typical helper event lines are ~150 chars),
        // short enough that a runaway helper writing megabytes of garbage doesn't blow the
        // event channel.
        const val MALFORMED_LINE_PREVIEW_LENGTH: Int = 200
    }
}

/**
 * Recording handle for the helper-process pair. `stop()` writes a Stop command, blocks until the
 * helper emits Stopped (or Error), and waits for the helper to exit.
 *
 * Mirrors `FfmpegRecorder.FfmpegRecordingHandle`'s three-state CAS / latch / result design so
 * concurrent stop() callers all observe the same outcome.
 */
private class GstRecordingHandle(
    private val process: Process,
    override val output: Path,
    private val json: Json,
    @Suppress("unused") private val startedEvent: Event.Started,
    private val stoppedRef: AtomicReference<Event.Stopped?>,
    private val errorRef: AtomicReference<Event.Error?>,
    private val stoppedLatch: CountDownLatch,
    private val readerThread: Thread,
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
            finished.countDown()
        }
    }

    private fun stopInternal() {
        // Send Stop command. The helper's stop watcher reads stdin; the next line ends its
        // read_line and triggers SIGTERM-with-`-e` on gst-launch.
        @Suppress("TooGenericExceptionCaught")
        try {
            val line = json.encodeToString(Command.serializer(), Command.Stop) + "\n"
            process.outputStream.write(line.toByteArray(StandardCharsets.UTF_8))
            process.outputStream.flush()
            // Closing stdin makes the helper's read_line return EOF for any subsequent
            // read — also a Stop signal in the helper's contract. Belt-and-braces against
            // a Stop command that gets buffered without the OS flush actually delivering.
            process.outputStream.close()
        } catch (_: IOException) {
            // Helper may have already exited; the latch wait below confirms.
        }
        if (!stoppedLatch.await(SHUTDOWN_GRACE_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException(
                "spectre-wayland-helper did not emit Stopped within ${SHUTDOWN_GRACE_SECONDS}s " +
                    "of stop(). Output at $output may be truncated or empty."
            )
        }
        errorRef.get()?.let { err ->
            throw IllegalStateException(
                "spectre-wayland-helper reported an error during recording: kind=${err.kind} " +
                    "message=${err.message}"
            )
        }
        readerThread.join(READER_JOIN_TIMEOUT_MS)
        if (!process.waitFor(PROCESS_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            throw IllegalStateException(
                "spectre-wayland-helper did not exit within ${PROCESS_EXIT_TIMEOUT_SECONDS}s " +
                    "after Stopped event. Output at $output may be in an undefined state."
            )
        }
        val exit = process.exitValue()
        if (exit != 0) {
            throw IllegalStateException(
                "spectre-wayland-helper exited with non-zero status $exit. Stopped event " +
                    "received was ${stoppedRef.get()}; check helper stderr (forwarded to this " +
                    "process's stderr) for the underlying cause."
            )
        }
    }

    private companion object {
        const val SHUTDOWN_GRACE_SECONDS: Long = 30
        const val PROCESS_EXIT_TIMEOUT_SECONDS: Long = 5
        const val READER_JOIN_TIMEOUT_MS: Long = 1_000
    }
}
