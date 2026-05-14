package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Frame
import java.awt.GraphicsEnvironment
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
