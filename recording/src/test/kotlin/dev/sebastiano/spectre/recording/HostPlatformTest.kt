package dev.sebastiano.spectre.recording

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFalse

class HostPlatformTest {
    @Test
    fun `Xvfb session with inherited Wayland socket is not misidentified as Wayland`() {
        // Before fix: detectWaylandSession alone declared Wayland when XDG_RUNTIME_DIR
        // contained socket, ignoring active DISPLAY / Xvfb session.
        assertFalse(
            HostPlatform.isWayland(),
            "Active X11/Xvfb should not be misrouted to Wayland/portal path",
        )
    }
}
