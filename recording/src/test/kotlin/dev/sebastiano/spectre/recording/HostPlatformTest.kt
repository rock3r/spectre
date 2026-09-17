package dev.sebastiano.spectre.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HostPlatformTest {
    @Test
    fun `isWayland is false off Linux and matches detectWaylandSession on Linux`() {
        if (!HostPlatform.isLinux()) {
            assertFalse(HostPlatform.isWayland())
            return
        }
        assertEquals(FfmpegBackend.detectWaylandSession(System::getenv), HostPlatform.isWayland())
    }
}
