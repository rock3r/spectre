package dev.sebastiano.spectre.recording.windows

import dev.sebastiano.spectre.recording.Recorder
import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import dev.sebastiano.spectre.recording.screencapturekit.WindowRecorder
import java.awt.Rectangle
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Windows MP4 recorder backed by Windows Graphics Capture. */
public class WindowsGraphicsCaptureRecorder
internal constructor(
    private val helperExtractor: WindowsGraphicsCaptureHelperBinaryExtractor,
    private val processFactory: ProcessFactory,
) : WindowRecorder, Recorder {

    public constructor() :
        this(DefaultWindowsGraphicsCaptureHelperBinaryExtractor.instance, SystemProcessFactory)

    override fun start(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        startWindow(window, windowOwnerPid, output, options, cropDevicePixels = null)

    override fun startCropped(
        window: TitledWindow,
        cropInWindow: Rectangle,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
        scaleX: Double,
        scaleY: Double,
    ): RecordingHandle {
        require(scaleX > 0.0 && scaleY > 0.0) {
            "scaleX/scaleY must be positive; got scaleX=$scaleX scaleY=$scaleY"
        }
        // WGC capture-item space is device pixels; convert from AWT user space.
        val cropDevice =
            Rectangle(
                (cropInWindow.x * scaleX).toInt(),
                (cropInWindow.y * scaleY).toInt(),
                (cropInWindow.width * scaleX).toInt().coerceAtLeast(1),
                (cropInWindow.height * scaleY).toInt().coerceAtLeast(1),
            )
        return startWindow(window, windowOwnerPid, output, options, cropDevicePixels = cropDevice)
    }

    private fun startWindow(
        window: TitledWindow,
        windowOwnerPid: Long,
        output: Path,
        options: RecordingOptions,
        cropDevicePixels: Rectangle?,
    ): RecordingHandle {
        val title = window.title
        require(!title.isNullOrBlank()) {
            "WindowsGraphicsCaptureRecorder requires a non-blank window title; got \"$title\"."
        }
        validateOptions(options)
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        if (cropDevicePixels != null) {
            System.err.println(
                "spectre: window+crop is fixed at start " +
                    "(${cropDevicePixels.x},${cropDevicePixels.y} " +
                    "${cropDevicePixels.width}x${cropDevicePixels.height} device px); " +
                    "surface move/resize mid-recording is not followed in v1."
            )
        }
        val helperPath = helperExtractor.extract()
        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Recording,
                    source = WindowsGraphicsCaptureSource.Window,
                    title = title,
                    ownerPid = windowOwnerPid,
                    crop = cropDevicePixels,
                    output = output,
                    fps = options.frameRate,
                    captureCursor = options.captureCursor,
                )
                .toArgv(helperPath)
        return startHelper(output, argv)
    }

    override fun start(
        region: Rectangle,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        require(region.width > 0 && region.height > 0) {
            "WindowsGraphicsCaptureRecorder requires a non-empty region; got $region."
        }
        validateOptions(options)
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        val helperPath = helperExtractor.extract()
        val argv =
            WindowsGraphicsCaptureArguments(
                    mode = WindowsGraphicsCaptureMode.Recording,
                    source = WindowsGraphicsCaptureSource.Region,
                    region = region,
                    output = output,
                    fps = options.frameRate,
                    captureCursor = options.captureCursor,
                )
                .toArgv(helperPath)
        return startHelper(output, argv)
    }

    private fun validateOptions(options: RecordingOptions) {
        require(options.screenIndex == RecordingOptions.DEFAULT_SCREEN_INDEX) {
            "WindowsGraphicsCaptureRecorder does not support RecordingOptions.screenIndex; " +
                "got ${options.screenIndex}."
        }
        require(options.codec == RecordingOptions.DEFAULT_CODEC) {
            "WindowsGraphicsCaptureRecorder does not support custom RecordingOptions.codec; " +
                "got ${options.codec}."
        }
    }

    private fun startHelper(output: Path, argv: List<String>): RecordingHandle {
        var process: Process? = null
        try {
            process = processFactory.start(argv)
            waitForReady(process, argv)
            return WindowsGraphicsCaptureRecordingHandle(process, output, argv)
        } catch (e: InterruptedException) {
            process?.destroyForcibly()
            Thread.currentThread().interrupt()
            throw e
        } catch (e: IOException) {
            throw IllegalStateException(
                "spectre-window-capture failed to start. Native Windows recording requires " +
                    ".NET 8 Desktop Runtime, Windows App Runtime 1.8, and Windows Graphics " +
                    "Capture support.",
                e,
            )
        } catch (e: IllegalStateException) {
            process?.destroyForcibly()
            throw e
        }
    }

    internal interface ProcessFactory {
        fun start(argv: List<String>): Process
    }

    private object SystemProcessFactory : ProcessFactory {
        override fun start(argv: List<String>): Process =
            ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .start()
    }

    private companion object {
        private const val READY: String = "READY"
        private const val READY_WAIT_MILLIS: Long = 3_500
        private const val EXIT_PROBE_WAIT_MILLIS: Long = 100

        fun waitForReady(process: Process, argv: List<String>) {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val readyLine = CompletableFuture.supplyAsync(reader::readLine)
            val line =
                try {
                    readyLine.get(READY_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (e: java.util.concurrent.TimeoutException) {
                    process.destroyForcibly()
                    throw IllegalStateException(
                        "Timed out waiting for spectre-window-capture to start recording. " +
                            "Argv: $argv",
                        e,
                    )
                }
            if (line == READY) {
                return
            }

            val exit =
                if (process.waitFor(EXIT_PROBE_WAIT_MILLIS, TimeUnit.MILLISECONDS)) {
                    process.exitValue()
                } else {
                    process.destroyForcibly()
                    -1
                }
            error(
                messageForWindowsGraphicsCaptureHelperExit(exit, argv) +
                    if (line == null) "" else " First stdout line: $line"
            )
        }
    }
}

private class WindowsGraphicsCaptureRecordingHandle(
    private val process: Process,
    override val output: Path,
    private val argv: List<String>,
) : RecordingHandle {

    private val stopLock = Any()
    @Volatile private var result: Result<Unit>? = null

    override val isStopped: Boolean
        get() = result?.isSuccess == true

    override fun stop() {
        synchronized(stopLock) {
            val existing = result
            if (existing != null) {
                existing.getOrThrow()
                return
            }

            val stopped = runCatching { stopInternal() }
            result = stopped
            stopped.getOrThrow()
        }
    }

    private fun stopInternal() {
        var interrupted = false
        var sentTerminationOurselves = false
        if (process.isAlive) {
            try {
                process.outputStream.use { stdin ->
                    stdin.write('q'.code)
                    stdin.flush()
                }
            } catch (_: IOException) {
                // The helper may have exited after isAlive was observed; waitFor below confirms.
            }

            val gracefulExit =
                try {
                    process.waitFor(STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                    false
                }
            if (!gracefulExit) {
                sentTerminationOurselves = true
                process.destroy()
                val terminatedAfterDestroy =
                    try {
                        process.waitFor(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    } catch (_: InterruptedException) {
                        interrupted = true
                        false
                    }
                if (!terminatedAfterDestroy) {
                    process.destroyForcibly()
                    val terminatedAfterForce =
                        try {
                            process.waitFor(FORCE_STOP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            interrupted = true
                            false
                        }
                    if (!terminatedAfterForce && process.isAlive) {
                        error(
                            "spectre-window-capture did not exit after destroyForcibly() " +
                                "within ${FORCE_STOP_TIMEOUT_SECONDS}s. Argv: $argv"
                        )
                    }
                }
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        if (process.isAlive) {
            error("spectre-window-capture is still running after stop. Argv: $argv")
        }
        val exit = process.exitValue()
        check(exit == 0 || sentTerminationOurselves) {
            messageForWindowsGraphicsCaptureHelperExit(exit, argv) +
                " The helper may also have been terminated externally after stop was requested."
        }
    }

    private companion object {
        private const val STOP_TIMEOUT_SECONDS: Long = 5
        private const val FORCE_STOP_TIMEOUT_SECONDS: Long = 2
    }
}
