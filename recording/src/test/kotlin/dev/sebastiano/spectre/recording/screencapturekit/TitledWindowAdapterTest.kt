package dev.sebastiano.spectre.recording.screencapturekit

import dev.sebastiano.spectre.recording.stabilizeMappedAwtFrames
import java.awt.Dialog
import java.awt.EventQueue
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Light tests for the public [Frame.asTitledWindow] adapter — the surface external callers use to
 * pass a `ComposeWindow` / `JFrame` / any `Frame` subclass into [ScreenCaptureKitRecorder.start].
 *
 * Constructing an AWT [Frame] hits `Window.<init>`, which can throw on headless hosts and can hang
 * while initialising AppKit on non-interactive macOS workers. Each test gates through
 * [assumeLiveAwtAvailable] so the default unit suite skips instead of wedging; run with
 * `-Dspectre.test.liveAwt=true` on macOS to exercise the adapter locally.
 */
class TitledWindowAdapterTest {

    @Test
    fun `Dialog adapter exposes title and bounds`() {
        assumeLiveAwtAvailable()
        val owner = Frame()
        val dialog = Dialog(owner, "before").apply { setBounds(11, 22, 333, 222) }
        try {
            val adapter = dialog.asTitledWindow()

            adapter.title = "after"

            assertEquals("after", adapter.title)
            assertEquals(dialog.bounds, adapter.bounds)
        } finally {
            dialog.dispose()
            owner.dispose()
        }
    }

    @Test
    fun `adapter reads through to the wrapped frame's title`() {
        assumeLiveAwtAvailable()

        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        assertEquals("MyApp", adapter.title)
    }

    @Test
    fun `adapter writes through to the wrapped frame's title`() {
        assumeLiveAwtAvailable()

        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        adapter.title = "Changed"

        assertEquals("Changed", frame.title)
    }

    @Test
    fun `adapter reads through to the wrapped frame's bounds`() {
        assumeLiveAwtAvailable()

        val frame = TestFrame(initialTitle = "MyApp").apply { setBounds(10, 20, 300, 200) }
        val adapter = frame.asTitledWindow()

        assertEquals(frame.bounds, adapter.bounds)
    }

    @Test
    fun `adapter writes null as empty string mirroring AWT behaviour`() {
        assumeLiveAwtAvailable()

        // AWT's Frame.setTitle(null) silently coerces to "" and treats getTitle() as "" thereafter.
        // The adapter preserves that behaviour rather than throwing or no-op'ing, so callers can
        // round-trip through the adapter without worrying about null handling.
        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        adapter.title = null

        assertEquals("", frame.title)
    }
}

private fun assumeLiveAwtAvailable() {
    if (System.getProperty("os.name").orEmpty().lowercase().contains("mac")) {
        assumeTrue(
            System.getProperty("spectre.test.liveAwt").toBoolean(),
            "AWT Frame tests are opt-in on macOS because AppKit initialisation can hang in " +
                "non-interactive workers",
        )
    }
    assumeFalse(GraphicsEnvironment.isHeadless(), "AWT Frame requires a graphical environment")
}

/** A subclass of [Frame] that doesn't try to allocate a peer — safe for headless unit tests. */
private class TestFrame(initialTitle: String) : Frame(initialTitle)

internal fun withSharedSckFrames(test: (Frame, Frame) -> Unit) {
    assumeLiveAwtAvailable()
    val titleChanges = java.util.concurrent.atomic.AtomicInteger()
    lateinit var left: Frame
    lateinit var right: Frame
    EventQueue.invokeAndWait {
        fun visible(bounds: Rectangle) =
            Frame("direct SCK shared title").apply {
                addPropertyChangeListener("title") { titleChanges.incrementAndGet() }
                isUndecorated = true
                this.bounds = bounds
                isVisible = true
            }
        left = visible(SCK_LEFT_FRAME)
        right = visible(SCK_RIGHT_FRAME)
    }
    try {
        stabilizeMappedAwtFrames(left to SCK_LEFT_FRAME, right to SCK_RIGHT_FRAME)
        test(left, right)
        assertEquals(0, titleChanges.get(), "capture changed an AWT title")
    } finally {
        EventQueue.invokeAndWait {
            left.dispose()
            right.dispose()
        }
    }
}

internal val SCK_LEFT_FRAME: Rectangle = Rectangle(40, 80, 300, 200)
internal val SCK_RIGHT_FRAME: Rectangle = Rectangle(410, 80, 300, 200)
