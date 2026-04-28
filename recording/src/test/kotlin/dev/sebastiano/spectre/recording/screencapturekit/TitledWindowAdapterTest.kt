package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.jupiter.api.Assumptions.assumeFalse

/**
 * Light tests for the public [Frame.asTitledWindow] adapter — the surface external callers use to
 * pass a `ComposeWindow` / `JFrame` / any `Frame` subclass into [ScreenCaptureKitRecorder.start].
 *
 * Constructing an AWT [Frame] hits `Window.<init>` which throws [java.awt.HeadlessException] on
 * machines without a display (headless CI). Each test gates on [GraphicsEnvironment.isHeadless] via
 * JUnit 5's [assumeFalse] — locally (where the recorder actually runs) the assertions execute; in
 * headless CI the tests are skipped rather than failing. The recorder itself is gated on macOS at
 * runtime, so headless CI never exercises it either way.
 */
class TitledWindowAdapterTest {

    @Test
    fun `adapter reads through to the wrapped frame's title`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "AWT Frame requires a graphical environment")

        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        assertEquals("MyApp", adapter.title)
    }

    @Test
    fun `adapter writes through to the wrapped frame's title`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "AWT Frame requires a graphical environment")

        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        adapter.title = "Changed"

        assertEquals("Changed", frame.title)
    }

    @Test
    fun `adapter writes null as empty string mirroring AWT behaviour`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "AWT Frame requires a graphical environment")

        // AWT's Frame.setTitle(null) silently coerces to "" and treats getTitle() as "" thereafter.
        // The adapter preserves that behaviour rather than throwing or no-op'ing, so callers can
        // round-trip through the adapter without worrying about null handling.
        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        adapter.title = null

        assertEquals("", frame.title)
    }
}

/** A subclass of [Frame] that doesn't try to allocate a peer — safe for headless unit tests. */
private class TestFrame(initialTitle: String) : Frame(initialTitle)
