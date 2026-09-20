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
                linuxHelperBundled = { true },
            ),
        )
        assertEquals(
            true,
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { true },
                linuxHelperBundled = { true },
            ),
        )
    }

    @Test
    fun `Linux is unusable when the bundled helper is absent`() {
        assertEquals(
            false,
            NativeWindowCaptureBridge.computePlatformCaptureUsable(
                isLinux = { true },
                gstLaunchAvailable = { true },
                linuxHelperBundled = { false },
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
                linuxHelperBundled = { true },
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

    @Test
    fun `overlapping waits keep independent inconclusive probe decisions`() {
        val table = WaitScopedProbeTable()
        val older = table.begin()
        var computes = 0
        val firstOlder =
            table.remember(older, cached = null) {
                computes += 1
                null
            }
        assertFalse(firstOlder.usableNow)
        val newer = table.begin()
        val secondOlder =
            table.remember(older, cached = null) {
                computes += 1
                true
            }
        assertFalse(secondOlder.usableNow)
        assertEquals(1, computes, "a newer wait must not clear an older wait's inconclusive probe")
        val firstNewer =
            table.remember(newer, cached = null) {
                computes += 1
                null
            }
        assertFalse(firstNewer.usableNow)
        assertEquals(2, computes)
        table.end(newer)
        val thirdOlder =
            table.remember(older, cached = null) {
                computes += 1
                true
            }
        assertFalse(thirdOlder.usableNow)
        assertEquals(2, computes, "ending a newer wait must not drop the older wait's decision")
        table.end(older)
        val retried = table.remember(table.begin(), cached = null) { true }
        assertTrue(retried.usableNow)
    }

    @Test
    fun `overlapping first waits reuse one timed-out probe`() {
        val table = WaitScopedProbeTable()
        val older = table.begin()
        val newer = table.begin()
        var computes = 0
        val first =
            table.remember(older, cached = null) {
                computes += 1
                null
            }
        assertFalse(first.usableNow)
        val second =
            table.remember(newer, cached = null) {
                computes += 1
                true
            }
        assertFalse(second.usableNow)
        assertEquals(1, computes, "a waiter behind the in-flight probe must reuse its timeout")
        table.end(newer)
        table.end(older)
        val later =
            table.remember(table.begin(), cached = null) {
                computes += 1
                true
            }
        assertTrue(later.usableNow)
        assertEquals(2, computes, "a later wait must still retry after overlapping waits end")
    }

    @Test
    fun `ended wait does not restore inconclusive probe state`() {
        val table = WaitScopedProbeTable()
        val waitId = table.begin()
        table.end(waitId)
        var computes = 0
        table.remember(waitId, cached = null) {
            computes += 1
            null
        }
        val late =
            table.remember(waitId, cached = null) {
                computes += 1
                true
            }
        assertTrue(late.usableNow)
        assertEquals(2, computes, "a cancelled wait must not republish a scoped slot after end")
    }

    @Test
    fun `unscoped wait id does not share an inconclusive probe slot`() {
        val table = WaitScopedProbeTable()
        var computes = 0
        table.remember(0L, cached = null) {
            computes += 1
            null
        }
        val retried =
            table.remember(0L, cached = null) {
                computes += 1
                true
            }
        assertTrue(retried.usableNow)
        assertEquals(2, computes, "waitId 0 is the unscoped fallback and must not reuse a slot")
    }
}
