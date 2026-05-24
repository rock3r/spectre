package dev.sebastiano.spectre.recording

import dev.sebastiano.spectre.recording.portal.CaptureBackend
import dev.sebastiano.spectre.recording.portal.CaptureTarget
import dev.sebastiano.spectre.recording.portal.Command
import dev.sebastiano.spectre.recording.portal.CursorMode
import dev.sebastiano.spectre.recording.portal.DefaultWaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.portal.Event
import dev.sebastiano.spectre.recording.portal.Region
import dev.sebastiano.spectre.recording.portal.SourceType
import dev.sebastiano.spectre.recording.portal.WaylandHelperBinaryExtractor
import dev.sebastiano.spectre.recording.portal.queryGtkFrameExtentsViaXprop
import dev.sebastiano.spectre.recording.screencapturekit.TitledWindow
import java.awt.Insets
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.io.path.deleteIfExists
import kotlinx.serialization.json.Json

/** Linux still screenshot backend backed by Spectre's bundled Linux capture helper. */
public class LinuxNativeScreenshotter
internal constructor(
    private val helperExtractor: WaylandHelperBinaryExtractor,
    private val processFactory: ProcessFactory,
    private val isWayland: () -> Boolean,
    private val displayNameProvider: () -> String?,
    private val frameExtentsLookup: (String) -> Insets?,
) : WindowScreenshotter, RegionScreenshotter {

    public constructor() :
        this(
            helperExtractor = DefaultWaylandHelperBinaryExtractor.instance,
            processFactory = SystemProcessFactory,
            isWayland = HostPlatform::isWayland,
            displayNameProvider = { System.getenv("DISPLAY") },
            frameExtentsLookup = ::queryGtkFrameExtentsViaXprop,
        )

    private val json = Json { ignoreUnknownKeys = true }

    override fun captureWindow(window: TitledWindow, windowOwnerPid: Long): BufferedImage {
        val title = window.title
        require(!title.isNullOrBlank()) {
            "LinuxNativeScreenshotter requires a non-blank window title; got \"$title\"."
        }
        val wayland = isWayland()
        val region =
            if (wayland) {
                val extents =
                    frameExtentsLookup(title)
                        ?: error(
                            "Could not determine WM frame extents for window '$title' on this " +
                                "Wayland session. The portal window screenshot path currently " +
                                "depends on `_GTK_FRAME_EXTENTS`, which is usually available for " +
                                "GTK/Mutter XWayland windows only. Missing `xprop`, a non-GTK " +
                                "toolkit, an unsupported compositor, or no matching X11 window " +
                                "can all produce this result; capture an explicit region instead."
                        )
                Rectangle(extents.left, extents.top, window.bounds.width, window.bounds.height)
            } else {
                window.bounds
            }
        return capture(
            Command.Screenshot(
                backend = if (wayland) CaptureBackend.WAYLAND_PORTAL else CaptureBackend.X11,
                target = CaptureTarget.WINDOW,
                sourceTypes = if (wayland) listOf(SourceType.WINDOW) else emptyList(),
                displayName =
                    if (wayland) null else displayNameProvider()?.takeIf { it.isNotBlank() },
                windowTitle = title,
                cursorMode = CursorMode.HIDDEN,
                region = region.toProtocolRegion(),
                output = tempPng().toAbsolutePath().toString(),
            )
        )
    }

    override fun captureRegion(region: Rectangle): BufferedImage {
        val wayland = isWayland()
        return capture(
            Command.Screenshot(
                backend = if (wayland) CaptureBackend.WAYLAND_PORTAL else CaptureBackend.X11,
                target = CaptureTarget.REGION,
                sourceTypes = if (wayland) listOf(SourceType.MONITOR) else emptyList(),
                displayName =
                    if (wayland) null else displayNameProvider()?.takeIf { it.isNotBlank() },
                cursorMode = CursorMode.HIDDEN,
                region = region.toProtocolRegion(),
                output = tempPng().toAbsolutePath().toString(),
            )
        )
    }

    private fun capture(command: Command.Screenshot): BufferedImage {
        val output = Path.of(command.output)
        var process: Process? = null
        var readerThread: Thread? = null
        try {
            output.toAbsolutePath().parent?.let(Files::createDirectories)
            val helperPath = helperExtractor.extract()
            process = processFactory.start(helperPath)
            val eventLatch = CountDownLatch(1)
            val eventResult = AtomicReference<Result<Event>?>()
            readerThread =
                thread(name = "linux-screenshot-helper-reader", isDaemon = true) {
                    try {
                        val result = runCatching {
                            BufferedReader(
                                    InputStreamReader(process.inputStream, StandardCharsets.UTF_8)
                                )
                                .use { readScreenshotTerminalEvent(it) }
                        }
                        eventResult.set(result)
                    } finally {
                        eventLatch.countDown()
                    }
                }
            writeCommand(process.outputStream, command)
            process.outputStream.close()
            if (!eventLatch.await(SCREENSHOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                throw IllegalStateException(
                    "Timed out after ${SCREENSHOT_TIMEOUT_MS}ms waiting for Linux screenshot " +
                        "helper."
                )
            }
            val event =
                eventResult.get()?.getOrThrow()
                    ?: error("Linux screenshot helper reader finished without an event result")
            when (event) {
                is Event.ScreenshotSaved -> Unit
                is Event.Error -> throw helperError(process, event)
                else -> error("Unexpected helper event during screenshot capture: $event")
            }
            waitForCleanExit(process)
            return ImageIO.read(output.toFile())
                ?: error("Linux screenshot helper did not produce a readable PNG at $output")
        } catch (e: InterruptedException) {
            cleanupFailedCapture(process, readerThread)
            Thread.currentThread().interrupt()
            throw IllegalStateException("Interrupted while waiting for Linux screenshot helper", e)
        } catch (e: IOException) {
            cleanupFailedCapture(process, readerThread)
            throw IllegalStateException("Linux screenshot helper failed", e)
        } catch (e: IllegalStateException) {
            cleanupFailedCapture(process, readerThread)
            throw e
        } catch (e: IllegalArgumentException) {
            cleanupFailedCapture(process, readerThread)
            throw e
        } finally {
            output.deleteIfExists()
        }
    }

    private fun helperError(process: Process, event: Event.Error): IllegalStateException {
        waitForProcessExitOrKill(process)
        return IllegalStateException(
            "spectre-wayland-helper reported an error during screenshot capture: " +
                "kind=${event.kind} message=${event.message}"
        )
    }

    private fun waitForCleanExit(process: Process) {
        if (!process.waitFor(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            error(
                "spectre-wayland-helper did not exit within ${PROCESS_EXIT_TIMEOUT_MS}ms " +
                    "after screenshot capture."
            )
        }
        val exit = process.exitValue()
        check(exit == 0) {
            "spectre-wayland-helper exited with non-zero status $exit during screenshot capture."
        }
    }

    private fun waitForProcessExitOrKill(process: Process) {
        if (!process.waitFor(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            process.waitFor(FORCED_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    private fun cleanupFailedCapture(process: Process?, readerThread: Thread?) {
        process?.destroyForcibly()
        @Suppress("SwallowedException")
        try {
            process?.inputStream?.close()
        } catch (_: IOException) {
            // Closing stdout is best-effort cleanup. The process kill above is the real guard.
        }
        @Suppress("SwallowedException")
        try {
            process?.waitFor(FORCED_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            readerThread?.join(READER_JOIN_TIMEOUT_MS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun readScreenshotTerminalEvent(reader: BufferedReader): Event {
        while (true) {
            val line = reader.readLine() ?: error("helper closed stdout before screenshot result")
            if (line.isBlank()) continue
            val event = json.decodeFromString(Event.serializer(), line)
            when (event) {
                is Event.ScreenshotSaved,
                is Event.Error -> return event
                is Event.Started,
                is Event.Stopped,
                is Event.FrameProgress -> {
                    // Ignore recording-only events until a terminal screenshot event arrives.
                }
            }
        }
    }

    private fun writeCommand(stream: OutputStream, command: Command) {
        val line = json.encodeToString(Command.serializer(), command) + "\n"
        stream.write(line.toByteArray(StandardCharsets.UTF_8))
        stream.flush()
    }

    internal interface ProcessFactory {
        fun start(helperPath: Path): Process
    }

    internal object SystemProcessFactory : ProcessFactory {
        override fun start(helperPath: Path): Process =
            ProcessBuilder(helperPath.toString())
                .redirectInput(ProcessBuilder.Redirect.PIPE)
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
    }

    private companion object {
        fun tempPng(): Path = Files.createTempFile("spectre-linux-screenshot-", ".png")

        fun Rectangle.toProtocolRegion(): Region =
            Region(x = x, y = y, width = width, height = height)
    }
}

private const val SCREENSHOT_TIMEOUT_MS: Long = 90_000
private const val PROCESS_EXIT_TIMEOUT_MS: Long = 5_000
private const val FORCED_EXIT_TIMEOUT_MS: Long = 2_000
private const val READER_JOIN_TIMEOUT_MS: Long = 2_000
