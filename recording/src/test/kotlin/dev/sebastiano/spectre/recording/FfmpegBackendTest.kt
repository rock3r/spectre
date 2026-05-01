package dev.sebastiano.spectre.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [FfmpegBackend]'s OS / session probe logic.
 *
 * The argv-building behaviour each backend wraps is covered in [FfmpegCliTest]; this file focuses
 * on the wrappers that decide WHICH builder runs and whether to short-circuit (e.g. the Wayland
 * guard that landed for #77).
 */
class FfmpegBackendTest {

    // -----------------------------------------------------------------------
    // detectWaylandSession — pure function, env-var matrix.
    //
    // Two signals, OR'd: XDG_SESSION_TYPE=wayland, or a non-blank WAYLAND_DISPLAY. Either
    // is enough; both being absent / non-Wayland is the only "not Wayland" outcome.
    // -----------------------------------------------------------------------

    @Test
    fun `detectWaylandSession returns true when XDG_SESSION_TYPE is wayland`() {
        val getenv = fakeEnv("XDG_SESSION_TYPE" to "wayland")
        assertTrue(FfmpegBackend.detectWaylandSession(getenv))
    }

    @Test
    fun `detectWaylandSession is case insensitive on XDG_SESSION_TYPE`() {
        // Some shells / login managers historically set this in upper case; tolerate it.
        val getenv = fakeEnv("XDG_SESSION_TYPE" to "WAYLAND")
        assertTrue(FfmpegBackend.detectWaylandSession(getenv))
    }

    @Test
    fun `detectWaylandSession returns true when WAYLAND_DISPLAY is set`() {
        // Catches setups where XDG_SESSION_TYPE is unset / wrong but a Wayland compositor is
        // running — e.g. manually-started compositors, nested compositors, user namespaces.
        val getenv = fakeEnv("WAYLAND_DISPLAY" to "wayland-0")
        assertTrue(FfmpegBackend.detectWaylandSession(getenv))
    }

    @Test
    fun `detectWaylandSession ignores blank WAYLAND_DISPLAY`() {
        // An empty string isn't a real signal — some init scripts export the var unset.
        val getenv = fakeEnv("WAYLAND_DISPLAY" to "")
        assertFalse(FfmpegBackend.detectWaylandSession(getenv))
    }

    @Test
    fun `detectWaylandSession returns false on Xorg session`() {
        val getenv = fakeEnv("XDG_SESSION_TYPE" to "x11", "DISPLAY" to ":0")
        assertFalse(FfmpegBackend.detectWaylandSession(getenv))
    }

    @Test
    fun `detectWaylandSession returns false when no relevant env vars are set`() {
        // No DISPLAY, no WAYLAND_DISPLAY, no XDG_SESSION_TYPE — typical for a barebones
        // SSH session or a CI job without a display server. We don't claim "Wayland" in this
        // case; the actual ffmpeg-side error will surface separately.
        assertFalse(FfmpegBackend.detectWaylandSession(fakeEnv()))
    }

    // -----------------------------------------------------------------------
    // checkNotWayland — throws-if-Wayland wrapper. The error message is part
    // of the contract because users will see it directly (this is what they
    // hit when they try to record on a Wayland session).
    // -----------------------------------------------------------------------

    @Test
    fun `checkNotWayland is a no-op on Xorg`() {
        // Should not throw. The fakeEnv with no Wayland signals models a healthy Xorg session.
        FfmpegBackend.checkNotWayland(fakeEnv("DISPLAY" to ":0"))
    }

    @Test
    fun `checkNotWayland throws with actionable message on Wayland`() {
        val ex =
            assertFailsWith<UnsupportedOperationException> {
                FfmpegBackend.checkNotWayland(fakeEnv("XDG_SESSION_TYPE" to "wayland"))
            }
        // The error has to point users somewhere — at minimum: name what we detected, the
        // Xorg-switch workaround, and the Wayland tracking issue.
        val msg = ex.message.orEmpty()
        assertTrue("Wayland" in msg, "Should name Wayland: $msg")
        assertTrue("Xorg" in msg, "Should suggest Xorg switch: $msg")
        assertTrue("issues/77" in msg, "Should link to the tracking issue: $msg")
    }

    // -----------------------------------------------------------------------
    // detect() — OS-name dispatch. The osName check is on the System property
    // so we can't easily mock it without process-level fiddling; we verify by
    // inspection that the current host's choice matches what the comments
    // claim, and trust the implementation's `when` branch coverage from
    // FfmpegCliTest's argv assertions for the actual builder selection.
    // -----------------------------------------------------------------------

    @Test
    fun `detect returns the correct backend for the current host OS`() {
        val osName = System.getProperty("os.name").orEmpty().lowercase()
        val expected =
            when {
                "mac" in osName -> FfmpegBackend.MacOsAvfoundation
                "windows" in osName -> FfmpegBackend.WindowsGdigrab
                "linux" in osName -> FfmpegBackend.LinuxX11Grab
                else -> null
            }
        if (expected == null) {
            // BSD, Solaris, anything else — must throw, naming the OS.
            val ex = assertFailsWith<UnsupportedOperationException> { FfmpegBackend.detect() }
            assertTrue(
                osName in ex.message.orEmpty().lowercase(),
                "Error message should name the unsupported OS: ${ex.message}",
            )
        } else {
            assertEquals(expected, FfmpegBackend.detect())
        }
    }
}

/** Helper: builds a `getenv`-shaped lambda backed by a fixed map. Unset vars return null. */
private fun fakeEnv(vararg entries: Pair<String, String>): (String) -> String? {
    val map = entries.toMap()
    return { name -> map[name] }
}
