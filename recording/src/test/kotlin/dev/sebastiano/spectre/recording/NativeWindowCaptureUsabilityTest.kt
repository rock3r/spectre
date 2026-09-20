package dev.sebastiano.spectre.recording

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NativeWindowCaptureUsabilityTest {

    @Test
    fun `Linux requires gst-launch before native stills are treated as usable`() {
        assertEquals(
            false,
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { false },
            ),
        )
        assertEquals(
            true,
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { true },
            ),
        )
    }

    @Test
    fun `non-Linux hosts stay usable without gst-launch`() {
        assertEquals(
            true,
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { false },
                gstLaunchAvailable = { false },
            ),
        )
    }

    @Test
    fun `inconclusive Linux probe is not cached as unavailable`() {
        assertNull(
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { null },
            )
        )
        val first = rememberCompletedProbe(cached = null, computed = null)
        assertFalse(first.usableNow)
        assertNull(first.cache)
        val second = rememberCompletedProbe(cached = first.cache, computed = true)
        assertTrue(second.usableNow)
        assertEquals(true, second.cache)
    }
}
