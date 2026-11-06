package dev.sebastiano.spectre.recording.portal

import dev.sebastiano.spectre.recording.RecordingHandle
import dev.sebastiano.spectre.recording.RecordingOptions
import java.awt.Dimension
import java.awt.Point
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GNOME Wayland window capture through Mutter window streams: bound once per window by focus,
 * checked against the window's size, and never replaced by a crop of a monitor capture.
 */
class WaylandWindowStreamsTest {

    @Test
    fun `finds the window in a transparent monitor-sized Mutter frame`() {
        // Seat probe on GNOME 50: a 400x300 window at (25,25) of a 2560x1440 RGBA frame, with a
        // translucent drop shadow around it.
        val frame = mutterFrame(window = Rectangle(25, 25, 400, 300), shadow = 6)

        assertEquals(Rectangle(25, 25, 400, 300), opaqueContentBounds(frame))
        assertNull(opaqueContentBounds(BufferedImage(10, 10, BufferedImage.TYPE_INT_ARGB)))
    }

    @Test
    fun `binds the focused window and keeps where it sits in the stream`() {
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val focus = FakeFocus(size = Dimension(400, 300))
        val binder = FocusWindowStreamBinder(session, focus)

        val stream = binder.bind("main", "k1", CursorMode.HIDDEN)

        assertEquals(Point(25, 25), stream.origin)
        assertEquals(1.0, stream.scale)
        assertEquals(listOf("main"), focus.focused)
        assertEquals(1, focus.restored, "focus must go back to the window that had it")
        assertTrue(session.unbound.isEmpty())
    }

    @Test
    fun `accepts a compositor-scaled stream`() {
        val session =
            FakeSession(
                frameWindow = Rectangle(50, 50, 800, 600),
                frameSize = Dimension(5120, 2880),
            )
        val binder = FocusWindowStreamBinder(session, FakeFocus(size = Dimension(400, 300)))

        assertEquals(2.0, binder.bind("main", "k1", CursorMode.HIDDEN).scale)
    }

    @Test
    fun `drops a binding that recorded another window and restores focus`() {
        // Another window kept keyboard focus, so Mutter recorded it instead.
        val session = FakeSession(frameWindow = Rectangle(25, 25, 900, 700))
        val focus = FakeFocus(size = Dimension(400, 300))
        val binder = FocusWindowStreamBinder(session, focus)

        val error =
            assertFailsWith<WaylandCaptureUnavailableException> {
                binder.bind("main", "k1", CursorMode.HIDDEN)
            }

        assertEquals(listOf("k1"), session.unbound)
        assertEquals(1, focus.restored)
        assertTrue(error.message.orEmpty().contains("900x700"), error.message)
    }

    @Test
    fun `captures reuse one binding and return only the window`() {
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val streams = streams(session, focus = FakeFocus(size = Dimension(400, 300)))

        val first = streams.capture("main")
        val second = streams.capture("main")

        assertEquals(1, session.binds)
        assertEquals(2, session.screenshots)
        assertEquals(400, first.width)
        assertEquals(300, first.height)
        assertEquals(0xFFFF0000.toInt(), second.getRGB(0, 0))
    }

    @Test
    fun `binds again when the session helper lost the stream`() {
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val streams = streams(session, focus = FakeFocus(size = Dimension(400, 300)))
        streams.capture("main")
        session.forgetNextScreenshot = true

        streams.capture("main")

        assertEquals(2, session.binds)
    }

    @Test
    fun `closing the window stops its stream`() {
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val closers = mutableMapOf<String, () -> Unit>()
        val streams =
            WaylandWindowStreams(
                session = session,
                binder = FocusWindowStreamBinder(session, FakeFocus(size = Dimension(400, 300))),
                windowSize = { Dimension(400, 300) },
                onWindowClosed = { window, action -> closers[window] = action },
                keyPrefix = "test",
                runInBackground = { it() },
            )
        streams.capture("main")

        closers.getValue("main")()

        assertEquals(listOf("test-1"), session.unbound)
    }

    @Test
    fun `closing a window does not wait for another window's binding`() {
        // windowClosed runs on the EDT, and binding waits on the EDT for focus. If closing took
        // the bind lock, the two would deadlock.
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val closers = java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()
        val closedWhileBinding = java.util.concurrent.atomic.AtomicBoolean(false)
        val focus =
            object : WindowFocus<String> {
                override fun size(window: String) = Dimension(400, 300)

                override fun focus(window: String): FocusHandoff {
                    if (window == "second") {
                        val closer = Thread { closers.getValue("first")() }
                        closer.start()
                        closer.join(CLOSE_TIMEOUT_MS)
                        closedWhileBinding.set(!closer.isAlive)
                    }
                    return FocusHandoff {}
                }
            }
        val streams =
            WaylandWindowStreams(
                session = session,
                binder = FocusWindowStreamBinder(session, focus),
                windowSize = { Dimension(400, 300) },
                onWindowClosed = { window, action -> closers[window] = action },
                keyPrefix = "test",
            )
        streams.capture("first")

        streams.capture("second")

        assertTrue(closedWhileBinding.get(), "closing must not block on a binding in progress")
    }

    @Test
    fun `a recording with the cursor gets its own stream and stills stay cursor-free`() {
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val streams = streams(session, focus = FakeFocus(size = Dimension(400, 300)))

        streams.capture("main")
        streams.startRecording("main", null, Path.of("/tmp/a.mp4"), RecordingOptions())
        streams.startRecording(
            "main",
            null,
            Path.of("/tmp/b.mp4"),
            RecordingOptions(captureCursor = false),
        )
        streams.capture("main")

        assertEquals(listOf(CursorMode.HIDDEN, CursorMode.EMBEDDED), session.bindCursorModes)
        assertEquals(listOf("test-2", "test-1"), session.recordings.map { it.key })
    }

    @Test
    fun `window recording crops inside the window's own stream`() {
        val session = FakeSession(frameWindow = Rectangle(25, 25, 400, 300))
        val streams = streams(session, focus = FakeFocus(size = Dimension(400, 300)))

        streams.startRecording(
            "main",
            cropInWindow = Rectangle(0, 30, 400, 270),
            output = Path.of("/tmp/out.mp4"),
            options = RecordingOptions(frameRate = 24),
        )

        val command = session.recordings.single()
        assertEquals(Region(25, 55, 400, 270), command.region)
        assertEquals(listOf(2560, 1440), command.streamSize)
        assertEquals(24, command.frameRate)
    }

    private fun streams(session: FakeSession, focus: FakeFocus) =
        WaylandWindowStreams(
            session = session,
            binder = FocusWindowStreamBinder(session, focus),
            windowSize = { focus.size },
            onWindowClosed = { _, _ -> },
            keyPrefix = "test",
        )

    private class FakeFocus(val size: Dimension) : WindowFocus<String> {
        val focused = mutableListOf<String>()
        var restored = 0

        override fun size(window: String): Dimension = size

        override fun focus(window: String): FocusHandoff {
            focused += window
            return FocusHandoff { restored += 1 }
        }
    }

    private class FakeSession(
        private val frameWindow: Rectangle,
        private val frameSize: Dimension = Dimension(2560, 1440),
    ) : WaylandWindowStreamSession {
        var binds = 0
        val bindCursorModes = mutableListOf<CursorMode>()
        var screenshots = 0
        var forgetNextScreenshot = false
        val unbound = mutableListOf<String>()
        val recordings = mutableListOf<Command.StartWindowRecording>()

        override fun bindFocused(
            key: String,
            cursorMode: CursorMode,
        ): Pair<Event.WindowBound, BufferedImage> {
            binds += 1
            bindCursorModes += cursorMode
            return Event.WindowBound(key, nodeId = 61, logicalSize = listOf(2560, 1440)) to frame()
        }

        override fun screenshot(key: String): BufferedImage {
            if (forgetNextScreenshot) {
                forgetNextScreenshot = false
                throw WaylandHelperErrorException(HELPER_ERROR_WINDOW_NOT_BOUND, "no stream")
            }
            screenshots += 1
            return frame()
        }

        override fun unbind(key: String) {
            unbound += key
        }

        override fun startRecording(
            command: Command.StartWindowRecording,
            output: Path,
        ): RecordingHandle {
            recordings += command
            return object : RecordingHandle {
                override val output: Path = output
                override val isStopped: Boolean = false

                override fun stop() = Unit
            }
        }

        private fun frame() =
            mutterFrame(frameWindow, shadow = 6, width = frameSize.width, height = frameSize.height)
    }
}

private const val CLOSE_TIMEOUT_MS = 2_000L

private fun mutterFrame(
    window: Rectangle,
    shadow: Int,
    width: Int = 2560,
    height: Int = 1440,
): BufferedImage {
    val frame = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    val graphics = frame.createGraphics()
    graphics.color = java.awt.Color(0, 0, 0, 60)
    graphics.fillRect(
        window.x - shadow,
        window.y - shadow,
        window.width + 2 * shadow,
        window.height + 2 * shadow,
    )
    graphics.composite = java.awt.AlphaComposite.Src
    graphics.color = java.awt.Color.RED
    graphics.fillRect(window.x, window.y, window.width, window.height)
    graphics.dispose()
    return frame
}
