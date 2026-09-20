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
        val first = rememberCompletedProbe(cached = null, waitScoped = null) { null }
        assertFalse(first.usableNow)
        assertNull(first.cache)
        assertEquals(false, first.waitScoped)
        val second = rememberCompletedProbe(cached = first.cache, waitScoped = null) { true }
        assertTrue(second.usableNow)
        assertEquals(true, second.cache)
        assertNull(second.waitScoped)
    }

    @Test
    fun `cached probe is reused without launching another compute`() {
        var computes = 0
        val reused =
            rememberCompletedProbe(cached = true, waitScoped = null) {
                computes += 1
                false
            }
        assertTrue(reused.usableNow)
        assertEquals(true, reused.cache)
        assertEquals(0, computes, "a completed cache must not start a second gst-launch probe")
    }

    @Test
    fun `inconclusive probe is reused for the current wait without a second compute`() {
        var computes = 0
        val first = rememberCompletedProbe(cached = null, waitScoped = null) { null }
        val reused =
            rememberCompletedProbe(cached = first.cache, waitScoped = first.waitScoped) {
                computes += 1
                true
            }
        assertFalse(reused.usableNow)
        assertNull(reused.cache)
        assertEquals(false, reused.waitScoped)
        assertEquals(0, computes, "a wait-scoped timeout must not start another 3s probe")
    }

    @Test
    fun `later wait can retry after clearing the wait-scoped inconclusive result`() {
        val first = rememberCompletedProbe(cached = null, waitScoped = null) { null }
        val retried = rememberCompletedProbe(cached = first.cache, waitScoped = null) { true }
        assertTrue(retried.usableNow)
        assertEquals(true, retried.cache)
    }
}
