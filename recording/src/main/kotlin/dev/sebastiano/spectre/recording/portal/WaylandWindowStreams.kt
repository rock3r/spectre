package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Dimension
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.WeakHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import javax.imageio.ImageIO
import kotlin.io.path.deleteIfExists
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real window capture on GNOME Wayland, without a portal dialog per window.
 *
 * The portal's window source asks the user to pick each window, and its restore token cannot match
 * a new Java window. GNOME Mutter's own `org.gnome.Mutter.ScreenCast` API records one window
 * without a dialog. Its stream renders only that window, so other windows on top of it do not show,
 * and it follows the window when it moves. Spectre binds one stream per window through the session
 * helper and keeps it for the window's lifetime.
 *
 * Spectre never substitutes a crop of a monitor capture for a window capture: when no window stream
 * can be bound, the capture fails with [WaylandCaptureUnavailableException].
 */
internal class WaylandWindowStreams<W : Any>(
    private val session: WaylandWindowStreamSession,
    private val binder: WaylandWindowStreamBinder<W>,
    private val windowSize: (W) -> Dimension,
    private val onWindowClosed: (W, () -> Unit) -> Unit,
    private val keyPrefix: String = "jvm-${ProcessHandle.current().pid()}",
) {
    private val bindLock = Any()
    private val bound = WeakHashMap<W, BoundWindowStream>()
    private val counter = AtomicLong()

    /** One frame of [window], cropped to the window's frame (title bar included). */
    fun capture(window: W): BufferedImage =
        withStream(window) { stream ->
            cropWindowFrame(session.screenshot(stream.key), stream, windowSize(window), null)
        }

    /** Records [window], or [cropInWindow] (AWT units from the window's top-left) inside it. */
    fun startRecording(
        window: W,
        cropInWindow: Rectangle?,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle =
        withStream(window) { stream ->
            val region = windowRegionInStream(stream, windowSize(window), cropInWindow)
            session.startRecording(
                Command.StartWindowRecording(
                    key = stream.key,
                    region = Region(region.x, region.y, region.width, region.height),
                    streamSize = listOf(stream.streamSize.width, stream.streamSize.height),
                    frameRate = options.frameRate,
                    output = output.toAbsolutePath().toString(),
                    codec = options.codec,
                ),
                output,
            )
        }

    private fun <T> withStream(window: W, action: (BoundWindowStream) -> T): T {
        val stream = streamFor(window)
        return try {
            action(stream)
        } catch (error: WaylandHelperErrorException) {
            if (error.kind != HELPER_ERROR_WINDOW_NOT_BOUND) throw error
            // The session helper restarted, or dropped this JVM's connection and its streams.
            synchronized(bindLock) { if (bound[window] == stream) bound.remove(window) }
            action(streamFor(window))
        }
    }

    private fun streamFor(window: W): BoundWindowStream =
        synchronized(bindLock) { bound[window] ?: bindNew(window) }

    private fun bindNew(window: W): BoundWindowStream {
        val key = "$keyPrefix-${counter.incrementAndGet()}"
        val stream = binder.bind(window, key)
        bound[window] = stream
        onWindowClosed(window) {
            synchronized(bindLock) { if (bound[window]?.key == key) bound.remove(window) }
            runCatching { session.unbind(key) }
        }
        return stream
    }
}

/** A Mutter window stream bound to one window, measured on its first frame. */
internal data class BoundWindowStream(
    val key: String,
    /** Top-left of the window inside the stream frame, in stream pixels. */
    val origin: Point,
    /** Stream pixels per AWT unit. */
    val scale: Double,
    /** Size of the whole stream frame, in pixels. */
    val streamSize: Dimension,
)

/**
 * How Spectre attaches a Mutter window stream to a window. [FocusWindowStreamBinder] selects the
 * window by keyboard focus; a lookup by Mutter window id can replace it without touching callers.
 */
internal fun interface WaylandWindowStreamBinder<W : Any> {
    fun bind(window: W, key: String): BoundWindowStream
}

/** The session-helper operations window capture uses. */
internal interface WaylandWindowStreamSession {
    /** Binds the focused window under [key]; returns the bind event and the first frame. */
    fun bindFocused(key: String): Pair<Event.WindowBound, BufferedImage>

    fun screenshot(key: String): BufferedImage

    fun unbind(key: String)

    fun startRecording(command: Command.StartWindowRecording, output: Path): RecordingHandle
}

/**
 * Binds the window that has keyboard focus. When [window] does not have it, [focus] gives it focus
 * briefly and gives it back afterwards. The first frame must show a window of [window]'s size;
 * otherwise another window had focus, and the binding is dropped instead of capturing it.
 */
internal class FocusWindowStreamBinder<W : Any>(
    private val session: WaylandWindowStreamSession,
    private val focus: WindowFocus<W>,
) : WaylandWindowStreamBinder<W> {
    override fun bind(window: W, key: String): BoundWindowStream {
        val expected = focus.size(window)
        val handoff = focus.focus(window)
        try {
            val (event, frame) = session.bindFocused(key)
            val content = opaqueContentBounds(frame)
            val scale = content?.let { matchingScale(it, expected, streamScale(frame, event)) }
            if (content == null || scale == null) {
                runCatching { session.unbind(key) }
                val recorded = content?.let { "${it.width}x${it.height}" } ?: "fully transparent"
                throw WaylandCaptureUnavailableException(
                    "Spectre could not bind a GNOME Mutter window stream to the window " +
                        "${focus.describe(window)} (${expected.width}x${expected.height}): the " +
                        "window Mutter recorded is $recorded, " +
                        "so another window probably had keyboard focus. Spectre focuses a " +
                        "window to bind it; make sure the window can take focus and is not " +
                        "covered by a modal dialog. Translucent windows cannot be bound yet."
                )
            }
            return BoundWindowStream(
                key = key,
                origin = Point(content.x, content.y),
                scale = scale,
                streamSize = Dimension(frame.width, frame.height),
            )
        } finally {
            handoff.restore()
        }
    }
}

/** Keyboard-focus control for [FocusWindowStreamBinder]. */
internal interface WindowFocus<W : Any> {
    fun size(window: W): Dimension

    /** Gives [window] keyboard focus when it lacks it. The handoff gives focus back. */
    fun focus(window: W): FocusHandoff

    fun describe(window: W): String = window.toString()
}

internal fun interface FocusHandoff {
    fun restore()
}

/**
 * [WindowFocus] through AWT. A window AWT does not report as focused within [timeoutMs] (a
 * non-focusable popup, for example) is still bound: the size check on the first frame decides.
 */
internal class AwtWindowFocus(
    private val timeoutMs: Long = DEFAULT_FOCUS_TIMEOUT_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : WindowFocus<Window> {
    override fun size(window: Window): Dimension = awtWindowSize(window)

    override fun describe(window: Window): String = window.describe()

    override fun focus(window: Window): FocusHandoff {
        val state = onEdt {
            FocusState(
                alreadyFocused = window.isFocused,
                previous = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                focusable = window.focusableWindowState,
            )
        }
        if (state.alreadyFocused) return FocusHandoff {}
        onEdt {
            if (!window.focusableWindowState) window.focusableWindowState = true
            window.toFront()
            window.requestFocus()
        }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline && !onEdt { window.isFocused }) {
            sleep(FOCUS_POLL_MS)
        }
        return FocusHandoff {
            onEdt {
                if (window.focusableWindowState != state.focusable) {
                    window.focusableWindowState = state.focusable
                }
                state.previous
                    ?.takeIf { it !== window && it.isShowing }
                    ?.let {
                        it.toFront()
                        it.requestFocus()
                    }
            }
        }
    }

    private data class FocusState(
        val alreadyFocused: Boolean,
        val previous: Window?,
        val focusable: Boolean,
    )

    private companion object {
        const val DEFAULT_FOCUS_TIMEOUT_MS: Long = 1_500
        const val FOCUS_POLL_MS: Long = 25
    }
}

/** [WaylandWindowStreamSession] over the session helper's unix socket. */
internal class HelperWindowStreamSession(private val client: WaylandSessionClient) :
    WaylandWindowStreamSession {
    override fun bindFocused(key: String): Pair<Event.WindowBound, BufferedImage> =
        withFrameFile { output ->
            val event = mutterCall { client.send(Command.BindFocusedWindow(key, output)) }
            check(event is Event.WindowBound) { "unexpected reply to bind_focused_window: $event" }
            event to readFrame(output)
        }

    override fun screenshot(key: String): BufferedImage = withFrameFile { output ->
        mutterCall { client.send(Command.WindowScreenshot(key, output)) }
        readFrame(output)
    }

    override fun unbind(key: String) {
        client.send(Command.UnbindWindow(key))
    }

    override fun startRecording(
        command: Command.StartWindowRecording,
        output: Path,
    ): RecordingHandle {
        output.toAbsolutePath().parent?.let(Files::createDirectories)
        return SessionRecordingHandle(mutterCall { client.startHeld(command) }, output)
    }

    private fun <T> withFrameFile(block: (String) -> T): T {
        val file = Files.createTempFile("spectre-wayland-window-", ".png")
        try {
            return block(file.toAbsolutePath().toString())
        } finally {
            file.deleteIfExists()
        }
    }

    private fun readFrame(output: String): BufferedImage =
        ImageIO.read(Path.of(output).toFile())
            ?: error("Wayland session helper did not produce a readable window frame at $output")

    private fun <T> mutterCall(block: () -> T): T =
        try {
            block()
        } catch (error: WaylandHelperErrorException) {
            if (error.kind == HELPER_ERROR_MUTTER_UNAVAILABLE) {
                throw WaylandCaptureUnavailableException(MUTTER_UNAVAILABLE_MESSAGE, error)
            }
            throw error
        }
}

internal const val MUTTER_UNAVAILABLE_MESSAGE: String =
    "Linux Wayland window capture needs GNOME's Mutter ScreenCast API, which this desktop does " +
        "not provide. Spectre does not fall back to the portal's window picker (it shows a " +
        "dialog for every new window) or to a crop of a monitor capture. Capture an explicit " +
        "screen region with screenshot(region) or startRegion, or run the tests under X11/Xvfb."

/** Default window streams, bound through the JVM's session helper. */
internal object DefaultWaylandWindowStreams {
    val instance: WaylandWindowStreams<Window> by lazy {
        val session = HelperWindowStreamSession(DefaultWaylandSessionClient.instance)
        WaylandWindowStreams(
            session = session,
            binder = FocusWindowStreamBinder(session, AwtWindowFocus()),
            windowSize = ::awtWindowSize,
            onWindowClosed = ::runWhenWindowCloses,
        )
    }
}

/**
 * Bounds of the fully opaque pixels in [frame]. Mutter draws the window at the top-left of a
 * monitor-sized buffer; the rest, and the window's drop shadow, are transparent.
 */
internal fun opaqueContentBounds(frame: BufferedImage): Rectangle? {
    if (!frame.colorModel.hasAlpha()) return Rectangle(0, 0, frame.width, frame.height)
    val alpha = frame.alphaRaster ?: return Rectangle(0, 0, frame.width, frame.height)
    var minX = Int.MAX_VALUE
    var minY = Int.MAX_VALUE
    var maxX = -1
    var maxY = -1
    val row = IntArray(frame.width)
    for (y in 0 until frame.height) {
        alpha.getSamples(0, y, frame.width, 1, 0, row)
        for (x in row.indices) {
            if (row[x] == OPAQUE_ALPHA) {
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                maxY = y
            }
        }
    }
    if (maxX < 0) return null
    return Rectangle(minX, minY, maxX - minX + 1, maxY - minY + 1)
}

/** Stream pixels per logical pixel, from the frame and Mutter's logical stream size. */
internal fun streamScale(frame: BufferedImage, event: Event.WindowBound): Double {
    val logicalWidth = event.logicalSize.firstOrNull() ?: 0
    return if (logicalWidth > 0) frame.width.toDouble() / logicalWidth else 1.0
}

/**
 * The scale at which [content] shows a window of [expected] AWT size: 1 (AWT units are stream
 * pixels) or [streamScale] (Xwayland scaled by the compositor). Null when neither fits.
 */
internal fun matchingScale(content: Rectangle, expected: Dimension, streamScale: Double): Double? =
    listOf(1.0, streamScale).distinct().firstOrNull { scale ->
        abs(content.width - expected.width * scale) <= SIZE_TOLERANCE_PX &&
            abs(content.height - expected.height * scale) <= SIZE_TOLERANCE_PX
    }

/** The window (or [cropInWindow] inside it) in stream pixels. */
internal fun windowRegionInStream(
    stream: BoundWindowStream,
    windowSize: Dimension,
    cropInWindow: Rectangle?,
): Rectangle {
    val inWindow = cropInWindow ?: Rectangle(0, 0, windowSize.width, windowSize.height)
    return Rectangle(
        stream.origin.x + (inWindow.x * stream.scale).roundToInt(),
        stream.origin.y + (inWindow.y * stream.scale).roundToInt(),
        (inWindow.width * stream.scale).roundToInt().coerceAtLeast(1),
        (inWindow.height * stream.scale).roundToInt().coerceAtLeast(1),
    )
}

/**
 * Cuts the window out of a Mutter frame. This is a crop of the window's own stream, not of a
 * monitor: nothing outside the window can show.
 */
internal fun cropWindowFrame(
    frame: BufferedImage,
    stream: BoundWindowStream,
    windowSize: Dimension,
    cropInWindow: Rectangle?,
): BufferedImage {
    val region =
        windowRegionInStream(stream, windowSize, cropInWindow)
            .intersection(Rectangle(0, 0, frame.width, frame.height))
    check(!region.isEmpty) {
        "The window lies outside its own Mutter stream frame (${frame.width}x${frame.height})"
    }
    val out = BufferedImage(region.width, region.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = out.createGraphics()
    try {
        graphics.drawImage(
            frame.getSubimage(region.x, region.y, region.width, region.height),
            0,
            0,
            null,
        )
    } finally {
        graphics.dispose()
    }
    return out
}

private const val OPAQUE_ALPHA = 255
private const val SIZE_TOLERANCE_PX = 2.0

private fun awtWindowSize(window: Window): Dimension = onEdt { window.size }

private fun Window.describe(): String =
    when (this) {
        is java.awt.Frame -> "\"$title\""
        is java.awt.Dialog -> "\"$title\""
        else -> javaClass.simpleName
    }

private fun runWhenWindowCloses(window: Window, action: () -> Unit) {
    window.addWindowListener(
        object : WindowAdapter() {
            override fun windowClosed(e: WindowEvent) {
                window.removeWindowListener(this)
                action()
            }
        }
    )
}

private fun <T> onEdt(block: () -> T): T {
    if (EventQueue.isDispatchThread()) return block()
    val result = AtomicReference<Result<T>>()
    EventQueue.invokeAndWait { result.set(runCatching(block)) }
    return result.get().getOrThrow()
}
