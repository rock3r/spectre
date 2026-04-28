package dev.sebastiano.spectre.recording.screencapturekit

import java.awt.Frame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Light tests for the public [Frame.asTitledWindow] adapter — the surface external callers use to
 * pass a `ComposeWindow` / `JFrame` / any `Frame` subclass into [ScreenCaptureKitRecorder.start].
 *
 * Uses a plain headless `Frame` subclass to avoid pulling AWT's display/peer machinery into a unit
 * test — we only care about the title getter/setter delegation here.
 */
class TitledWindowAdapterTest {

    @Test
    fun `adapter reads through to the wrapped frame's title`() {
        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        assertEquals("MyApp", adapter.title)
    }

    @Test
    fun `adapter writes through to the wrapped frame's title`() {
        val frame = TestFrame(initialTitle = "MyApp")
        val adapter = frame.asTitledWindow()

        adapter.title = "Changed"

        assertEquals("Changed", frame.title)
    }

    @Test
    fun `adapter writes null as empty string mirroring AWT behaviour`() {
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
