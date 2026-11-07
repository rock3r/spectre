package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Visual-idle sampling must surface a missing Wayland grant or an unusable Wayland window capture
 * at once, instead of counting an unsampleable frame until the wait times out.
 */
class WaylandCaptureFailFastTest {

    private open class FakeUnavailable(message: String) : IllegalStateException(message)

    private class FakeConsentMissing(message: String) : FakeUnavailable(message)

    private val names = setOf(FakeUnavailable::class.java.name)

    @Test
    fun `a fail-fast exception is recognised by class name`() {
        assertTrue(isWaylandCaptureFailFast(FakeUnavailable("no Mutter"), names))
    }

    @Test
    fun `a subclass of a fail-fast exception is recognised`() {
        assertTrue(isWaylandCaptureFailFast(FakeConsentMissing("no grant"), names))
    }

    @Test
    fun `a fail-fast exception wrapped as a cause is recognised`() {
        val wrapped = IllegalStateException("bridge failed", FakeConsentMissing("no grant"))
        assertTrue(isWaylandCaptureFailFast(wrapped, names))
    }

    @Test
    fun `other capture failures are not fail-fast`() {
        assertFalse(isWaylandCaptureFailFast(IllegalStateException("gst-launch exited"), names))
    }

    @Test
    fun `the default names are the recording module's Wayland exceptions`() {
        assertTrue(
            "dev.sebastiano.spectre.recording.portal.WaylandCaptureUnavailableException" in
                WAYLAND_CAPTURE_FAIL_FAST_EXCEPTIONS
        )
        // A popup that Wayland cannot capture yet is an UnsupportedOperationException, which
        // visual idle otherwise treats as one unsampleable frame.
        assertTrue(
            "dev.sebastiano.spectre.recording.portal.WaylandWindowCaptureUnsupportedException" in
                WAYLAND_CAPTURE_FAIL_FAST_EXCEPTIONS
        )
    }

    @Test
    fun `an unsupported-operation subclass is recognised by class name`() {
        class FakeUnsupported(message: String) : UnsupportedOperationException(message)

        assertTrue(
            isWaylandCaptureFailFast(
                FakeUnsupported("popup"),
                setOf(FakeUnsupported::class.java.name),
            )
        )
        assertFalse(isWaylandCaptureFailFast(UnsupportedOperationException("other"), names))
    }
}
