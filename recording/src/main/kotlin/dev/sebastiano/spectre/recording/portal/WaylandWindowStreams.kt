package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import dev.sebastiano.spectre.recording.screencapturekit.asFramedWindow
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
    /** Runs the helper unbind of a closed window off the EDT. */
    private val runInBackground: (() -> Unit) -> Unit = ::runOnDaemonThread,
    private val isEventDispatchThread: () -> Boolean = EventQueue::isDispatchThread,
) {
    /**
     * Serialises bindings: each one may move keyboard focus. Held while binding, never on close.
     */
    private val bindLock = Any()
    /** Guards [bound]; held only for map reads and writes, never while waiting on anything. */
    private val mapLock = Any()
    private val bound = WeakHashMap<W, MutableMap<CursorMode, BoundWindowStream>>()
    private val counter = AtomicLong()

    /** One frame of [window], cropped to the window's frame (title bar included), no cursor. */
    fun capture(window: W): BufferedImage =
        withStream(window, CursorMode.HIDDEN) { stream ->
            cropWindowFrame(session.screenshot(stream.key), stream, windowSize(window), null)
        }

    /**
     * Records [window], or [cropInWindow] (AWT units from the window's top-left) inside it. Mutter
     * fixes the cursor mode when it binds a stream, so a recording that shows the pointer uses a
     * second stream; stills keep the cursor-free one.
     */
    fun startRecording(
        window: W,
        cropInWindow: Rectangle?,
        output: Path,
        options: RecordingOptions,
    ): RecordingHandle {
        val cursor = if (options.captureCursor) CursorMode.EMBEDDED else CursorMode.HIDDEN
        return withStream(window, cursor) { stream ->
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
    }

    private fun <T> withStream(
        window: W,
        cursor: CursorMode,
        action: (BoundWindowStream) -> T,
    ): T {
        val stream = streamFor(window, cursor)
        return try {
            action(stream)
        } catch (error: WaylandHelperErrorException) {
            if (error.kind != HELPER_ERROR_WINDOW_NOT_BOUND) throw error
            // The session helper restarted, or dropped this JVM's connection and its streams.
            synchronized(mapLock) { forget(window, stream.key) }
            action(streamFor(window, cursor))
        }
    }

    private fun streamFor(window: W, cursor: CursorMode): BoundWindowStream {
        synchronized(mapLock) { bound[window]?.get(cursor) }
            ?.let {
                return it
            }
        // Binding waits on the EDT for focus. On the EDT it could never finish, and waiting for
        // another thread's binding there would deadlock it.
        if (isEventDispatchThread()) {
            throw WaylandCaptureUnavailableException(BIND_ON_EDT_MESSAGE)
        }
        synchronized(bindLock) {
            synchronized(mapLock) { bound[window]?.get(cursor) }
                ?.let {
                    return it
                }
            return bindNew(window, cursor)
        }
    }

    private fun bindNew(window: W, cursor: CursorMode): BoundWindowStream {
        val key = "$keyPrefix-${counter.incrementAndGet()}"
        val stream = binder.bind(window, key, cursor)
        synchronized(mapLock) { bound.getOrPut(window) { mutableMapOf() }[cursor] = stream }
        // Runs on the EDT for AWT windows. A binding in progress waits on the EDT for focus, so
        // this must take neither the bind lock nor the session client's lock on the EDT.
        onWindowClosed(window) {
            synchronized(mapLock) { forget(window, key) }
            unbindInBackground(key)
        }
        return stream
    }

    /** Drops the binding [key] of [window]. Callers hold [mapLock]. */
    private fun forget(window: W, key: String) {
        val streams = bound[window] ?: return
        streams.entries.removeIf { it.value.key == key }
        if (streams.isEmpty()) bound.remove(window)
    }

    private fun unbindInBackground(key: String) {
        runInBackground { runCatching { session.unbind(key) } }
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
    fun bind(window: W, key: String, cursor: CursorMode): BoundWindowStream
}

/** The session-helper operations window capture uses. */
internal interface WaylandWindowStreamSession {
    /** Binds the focused window under [key]; returns the bind event and the first frame. */
    fun bindFocused(key: String, cursorMode: CursorMode): Pair<Event.WindowBound, BufferedImage>

    fun screenshot(key: String): BufferedImage

    fun unbind(key: String)

    fun startRecording(command: Command.StartWindowRecording, output: Path): RecordingHandle
}

/**
 * Binds the window that has keyboard focus. When [window] does not have it, [focus] gives it focus
 * briefly and gives it back afterwards. The window must show at [window]'s size in the stream;
 * otherwise another window had focus, and the binding is dropped instead of capturing it. Mutter
 * can hand over frames before the window is drawn into its stream, so the binder polls frames for
 * up to [paintWaitMs] before it decides.
 */
internal class FocusWindowStreamBinder<W : Any>(
    private val session: WaylandWindowStreamSession,
    private val focus: WindowFocus<W>,
    private val paintWaitMs: Long = DEFAULT_PAINT_WAIT_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : WaylandWindowStreamBinder<W> {
    override fun bind(window: W, key: String, cursor: CursorMode): BoundWindowStream {
        focus.unbindableReason(window)?.let { throw WaylandWindowCaptureUnsupportedException(it) }
        val expected = focus.size(window)
        val handoff = focus.focus(window)
        try {
            if (!handoff.confirmed) {
                // Mutter records the focused window; matching sizes cannot prove it is this one.
                throw WaylandCaptureUnavailableException(
                    "Spectre could not bind a GNOME Mutter window stream to the window " +
                        "${focus.describe(window)}: it did not get keyboard focus, and Mutter " +
                        "records the focused window. Make sure the window can take focus " +
                        "(non-focusable popups cannot be bound yet) and that no modal dialog " +
                        "holds it." +
                        handoff.detail?.let { " ($it)" }.orEmpty()
                )
            }
            val (event, firstFrame) = session.bindFocused(key, cursor)
            var bound = false
            try {
                val (frame, content, scale) = waitForWindow(key, event, firstFrame, expected)
                if (content == null || scale == null) {
                    val recorded =
                        content?.let { "${it.width}x${it.height}" } ?: "fully transparent"
                    throw WaylandCaptureUnavailableException(
                        "Spectre could not bind a GNOME Mutter window stream to the window " +
                            "${focus.describe(window)} (${expected.width}x${expected.height}): " +
                            "the window Mutter recorded is $recorded, " +
                            "so another window probably had keyboard focus. Spectre focuses a " +
                            "window to bind it; make sure the window can take focus and is not " +
                            "covered by a modal dialog. Translucent windows cannot be bound yet."
                    )
                }
                bound = true
                return BoundWindowStream(
                    key = key,
                    origin = Point(content.x, content.y),
                    scale = scale,
                    streamSize = Dimension(frame.width, frame.height),
                )
            } finally {
                if (!bound) runCatching { session.unbind(key) }
            }
        } finally {
            handoff.restore()
        }
    }

    /** Polls stream frames until the window shows at [expected] size, or [paintWaitMs] passes. */
    private fun waitForWindow(
        key: String,
        event: Event.WindowBound,
        firstFrame: BufferedImage,
        expected: Dimension,
    ): Triple<BufferedImage, Rectangle?, Double?> {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(paintWaitMs)
        var frame = firstFrame
        while (true) {
            val content = opaqueContentBounds(frame)
            val scale = content?.let { matchingScale(it, expected, streamScale(frame, event)) }
            if (scale != null || System.nanoTime() >= deadline) {
                return Triple(frame, content, scale)
            }
            sleep(PAINT_POLL_MS)
            frame = session.screenshot(key)
        }
    }

    private companion object {
        const val DEFAULT_PAINT_WAIT_MS: Long = 1_000
        const val PAINT_POLL_MS: Long = 50
    }
}

/** Keyboard-focus control for [FocusWindowStreamBinder]. */
internal interface WindowFocus<W : Any> {
    /** Why [window] can never be bound by focus, or null when it can. Checked before any change. */
    fun unbindableReason(window: W): String? = null

    fun size(window: W): Dimension

    /** Gives [window] keyboard focus when it lacks it. The handoff gives focus back. */
    fun focus(window: W): FocusHandoff

    fun describe(window: W): String = window.toString()
}

/**
 * The result of [WindowFocus.focus]: whether [window focus][confirmed] was confirmed, and, when it
 * was not, a [detail] that says why when one is known.
 */
internal class FocusHandoff(
    val confirmed: Boolean,
    val detail: String? = null,
    private val restoreFocus: () -> Unit,
) {
    /** Gives focus back to the window that had it. */
    fun restore() {
        restoreFocus()
    }
}

/**
 * [WindowFocus] through AWT. Focus counts as confirmed only when AWT reports the window focused
 * within [timeoutMs]; a non-focusable popup is made focusable for that moment.
 *
 * On GNOME's Xwayland, Mutter ignores a window's own `toFront`/`requestFocus` once another window,
 * or no window, holds focus. When AWT does not confirm focus soon, [activate] asks the window
 * manager to activate the window (an EWMH `_NET_ACTIVE_WINDOW` request with a current timestamp)
 * and AWT must confirm focus again. Giving focus back uses the same path.
 */
internal class AwtWindowFocus(
    private val timeoutMs: Long = DEFAULT_FOCUS_TIMEOUT_MS,
    private val sleep: (Long) -> Unit = Thread::sleep,
    private val activate: ((Window) -> Unit)? = null,
) : WindowFocus<Window> {
    override fun size(window: Window): Dimension = awtWindowSize(window)

    override fun describe(window: Window): String = window.describe()

    /**
     * XAWT never gives a window that is not a Frame or Dialog (a `JWindow` popup, a plain AWT
     * [Window]) X keyboard focus: it reports such a window focused while X focus stays on its owner
     * frame (`XWindowPeer.requestWindowFocus`). Mutter would record the owner, so these windows
     * cannot be bound by focus.
     */
    override fun unbindableReason(window: Window): String? =
        if (window.isFrameOrDialog()) null else POPUP_UNSUPPORTED_MESSAGE

    override fun focus(window: Window): FocusHandoff {
        val state = onEdt {
            FocusState(
                alreadyFocused = window.isFocused,
                previous = KeyboardFocusManager.getCurrentKeyboardFocusManager().focusedWindow,
                focusable = window.focusableWindowState,
            )
        }
        if (state.alreadyFocused) return FocusHandoff(confirmed = true) {}
        return acquireFocus(
            takeFocus = {
                onEdt {
                    if (!window.focusableWindowState) window.focusableWindowState = true
                    window.toFront()
                    window.requestFocus()
                }
            },
            isFocused = { onEdt { window.isFocused } },
            restore = {
                onEdt {
                    if (window.focusableWindowState != state.focusable) {
                        window.focusableWindowState = state.focusable
                    }
                }
                state.previous?.takeIf { it !== window }?.let(::giveFocusBack)
            },
            timeoutMs = timeoutMs,
            sleep = sleep,
            activate = activate?.let { activate -> { activate(window) } },
        )
    }

    /** Gives focus back to [previous], a window of this JVM that had it before the binding. */
    private fun giveFocusBack(previous: Window) {
        if (!onEdt { previous.isShowing }) return
        // A popup's focus lives on its owner frame; only frames and dialogs are activated.
        if (activate == null || !previous.isFrameOrDialog()) {
            onEdt {
                previous.toFront()
                previous.requestFocus()
            }
            return
        }
        acquireFocus(
            takeFocus = {
                onEdt {
                    previous.toFront()
                    previous.requestFocus()
                }
            },
            isFocused = { onEdt { previous.isFocused } },
            restore = {},
            timeoutMs = timeoutMs,
            sleep = sleep,
            activate = { activate(previous) },
        )
    }

    private data class FocusState(
        val alreadyFocused: Boolean,
        val previous: Window?,
        val focusable: Boolean,
    )

    private companion object {
        const val DEFAULT_FOCUS_TIMEOUT_MS: Long = 1_500
    }
}

/**
 * Activates a window of this JVM through the session helper: an EWMH `_NET_ACTIVE_WINDOW` request
 * for the Xwayland client window at the window's client-area frame, owned by this process.
 */
internal class HelperWindowActivator(
    private val client: WaylandSessionClient,
    private val displayName: () -> String? = { System.getenv("DISPLAY") },
) {
    fun activate(window: Window) {
        val frame = window.asFramedWindow(isWindows = false, clientAreaOnly = true).deviceFrame
        val event =
            client.send(
                Command.ActivateWindow(
                    displayName = displayName()?.takeIf { it.isNotBlank() },
                    frame = Region(frame.x, frame.y, frame.width, frame.height),
                    ownerPid = ProcessHandle.current().pid(),
                )
            )
        check(event is Event.WindowActivated) { "unexpected reply to activate_window: $event" }
    }
}

/** [WaylandWindowStreamSession] over the session helper's unix socket. */
internal class HelperWindowStreamSession(private val client: WaylandSessionClient) :
    WaylandWindowStreamSession {
    override fun bindFocused(
        key: String,
        cursorMode: CursorMode,
    ): Pair<Event.WindowBound, BufferedImage> = withFrameFile { output ->
        val event = mutterCall { client.send(Command.BindFocusedWindow(key, output, cursorMode)) }
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

/**
 * Takes focus and polls [isFocused] until [timeoutMs]. When [activate] is given and the window's
 * own request does not take focus within [OWN_REQUEST_WAIT_MS], [activate] asks the window manager
 * instead, and [isFocused] is polled again for up to [timeoutMs]. A failed activation leaves focus
 * unconfirmed, with the reason in [FocusHandoff.detail].
 *
 * If anything throws on the way (a capture whose budget ran out is interrupted here), [restore]
 * runs before the error propagates, so a popup never stays focusable and focus goes back to the
 * window that had it.
 */
internal fun acquireFocus(
    takeFocus: () -> Unit,
    isFocused: () -> Boolean,
    restore: () -> Unit,
    timeoutMs: Long,
    sleep: (Long) -> Unit,
    activate: (() -> Unit)? = null,
): FocusHandoff {
    var handedOff = false
    try {
        takeFocus()
        val ownWaitMs = if (activate == null) timeoutMs else minOf(timeoutMs, OWN_REQUEST_WAIT_MS)
        var focused = pollFocus(isFocused, ownWaitMs, sleep)
        var detail: String? = null
        if (!focused && activate != null) {
            detail = runActivation(activate)
            if (detail == null) focused = pollFocus(isFocused, timeoutMs, sleep)
        }
        val handoff = FocusHandoff(confirmed = focused, detail = detail, restoreFocus = restore)
        handedOff = true
        return handoff
    } finally {
        // On any error the caller never gets a handoff to restore, so restore here.
        if (!handedOff) runCatching(restore)
    }
}

private fun pollFocus(isFocused: () -> Boolean, timeoutMs: Long, sleep: (Long) -> Unit): Boolean {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
    var focused = isFocused()
    while (System.nanoTime() < deadline && !focused) {
        sleep(FOCUS_POLL_MS)
        focused = isFocused()
    }
    return focused
}

/**
 * Runs [activate]; returns why it failed, or null. An interrupt still propagates, and so does a
 * [WaylandCaptureUnavailableException] (a missing portal grant, no Mutter): those already say
 * clearly why capture cannot work, so they must not read as "the window did not get focus".
 */
@Suppress("TooGenericExceptionCaught") // Any other helper or X11 failure only means "no focus".
private fun runActivation(activate: () -> Unit): String? =
    try {
        activate()
        null
    } catch (error: InterruptedException) {
        throw error
    } catch (error: WaylandCaptureUnavailableException) {
        throw error
    } catch (error: Exception) {
        if (Thread.currentThread().isInterrupted) {
            throw InterruptedException(error.message).apply { initCause(error) }
        }
        "window-manager activation failed: $error"
    }

/** How long a window's own focus request gets before Spectre asks the window manager. */
private const val OWN_REQUEST_WAIT_MS: Long = 300

private const val FOCUS_POLL_MS: Long = 25

internal const val POPUP_UNSUPPORTED_MESSAGE: String =
    "Linux Wayland cannot capture a popup window (a JWindow, or any window that is not a Frame or " +
        "Dialog) yet: Spectre binds a GNOME Mutter window stream to the focused window, and AWT " +
        "keeps a popup's keyboard focus on its owner frame. Spectre does not substitute a crop of " +
        "the owner or of the monitor. Tracked in https://github.com/rock3r/spectre/issues/640. " +
        "Capture the owner window, an explicit screen region with screenshot(region), or run " +
        "under X11/Xvfb."

internal const val BIND_ON_EDT_MESSAGE: String =
    "Spectre cannot bind a GNOME Mutter window stream on the AWT event dispatch thread: binding " +
        "focuses the window and waits for the event dispatch thread. Capture the window once " +
        "from another thread; later captures of it also work on the event dispatch thread."

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
            binder =
                FocusWindowStreamBinder(
                    session,
                    AwtWindowFocus(
                        activate =
                            HelperWindowActivator(DefaultWaylandSessionClient.instance)::activate
                    ),
                ),
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

private fun runOnDaemonThread(action: () -> Unit) {
    Thread.ofPlatform().daemon().name("spectre-wayland-window-unbind").start(action)
}

private fun Window.isFrameOrDialog(): Boolean = this is java.awt.Frame || this is java.awt.Dialog

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
