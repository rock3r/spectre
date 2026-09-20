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
        val first = rememberCompletedProbe(cached = null) { null }
        assertFalse(first.usableNow)
        assertNull(first.cache)
        val second = rememberCompletedProbe(cached = first.cache) { true }
        assertTrue(second.usableNow)
        assertEquals(true, second.cache)
    }

    @Test
    fun `cached probe is reused without launching another compute`() {
        var computes = 0
        val reused =
            rememberCompletedProbe(cached = true) {
                computes += 1
                false
            }
        assertTrue(reused.usableNow)
        assertEquals(true, reused.cache)
        assertEquals(0, computes, "a completed cache must not start a second gst-launch probe")
    }
}
