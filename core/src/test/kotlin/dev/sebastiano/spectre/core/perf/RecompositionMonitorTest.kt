@file:OptIn(ExperimentalSpectreApi::class)

package dev.sebastiano.spectre.core.perf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking

/**
 * Unit tests for [RecompositionMonitor]'s counter math via the internal `recordRecomposition` seam.
 * **Live `Recomposer` wire-up is not yet covered by automated tests** — a `sample-desktop`
 * integration that drives an actual ComposeWindow recomposing and asserts the monitor counts is
 * planned as a follow-up.
 */
class RecompositionMonitorTest {

    @Test
    fun `total counts every recordRecomposition call across surfaces`() {
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("popup:0")
        assertEquals(3, monitor.total)
        monitor.close()
    }

    @Test
    fun `ratePerSecond reflects only entries inside the window`() {
        val timeSource = TestTimeSource()
        val monitor = RecompositionMonitor(windowDuration = 1.seconds, timeSource = timeSource)
        repeat(5) { monitor.recordRecomposition("window:0") }
        assertEquals(5.0, monitor.ratePerSecond)
        timeSource += 2.seconds
        assertEquals(0.0, monitor.ratePerSecond)
        monitor.close()
    }

    @Test
    fun `ratePerSecond uses windowDuration as the denominator`() {
        val timeSource = TestTimeSource()
        val monitor =
            RecompositionMonitor(windowDuration = 500.milliseconds, timeSource = timeSource)
        repeat(10) { monitor.recordRecomposition("window:0") }
        // 10 recompositions in a 500ms window → 20/s rate.
        assertEquals(20.0, monitor.ratePerSecond)
        monitor.close()
    }

    @Test
    fun `perSurface lists each surface separately with its own counters`() {
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("popup:0")
        val byId = monitor.perSurface().associateBy { it.surfaceId }
        assertEquals(2L, byId.getValue("window:0").total)
        assertEquals(1L, byId.getValue("popup:0").total)
        monitor.close()
    }

    @Test
    fun `activeSurfaces tracks the distinct surface count`() {
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        assertEquals(0, monitor.activeSurfaces)
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("window:0")
        assertEquals(1, monitor.activeSurfaces)
        monitor.recordRecomposition("popup:0")
        assertEquals(2, monitor.activeSurfaces)
        monitor.close()
    }

    @Test
    fun `reset zeroes counters but preserves surface registrations`() {
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("popup:0")
        monitor.reset()
        assertEquals(0, monitor.total)
        assertEquals(0.0, monitor.ratePerSecond)
        // Surfaces remain registered so subsequent observations land in the same buckets.
        assertEquals(2, monitor.activeSurfaces)
        monitor.close()
    }

    @Test
    fun `snapshot captures a coherent view at one moment`() {
        val timeSource = TestTimeSource()
        val monitor = RecompositionMonitor(windowDuration = 1.seconds, timeSource = timeSource)
        repeat(3) { monitor.recordRecomposition("window:0") }
        val snapshot = monitor.snapshot()
        assertEquals(3, snapshot.total)
        assertEquals(3.0, snapshot.ratePerSecond)
        assertEquals(1, snapshot.activeSurfaces)
        assertEquals(1.seconds, snapshot.windowDuration)
        monitor.close()
    }

    @Test
    fun `detach clears the rate window for that surface without resetting total`() {
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("window:0")
        monitor.recordRecomposition("window:0")
        assertEquals(3.0, monitor.ratePerSecond)
        // detach is the internal hook the StateFlow reconciler uses when a surface disappears.
        // It must immediately zero the rate so awaitRateBelow doesn't keep failing against the
        // ghost of a closed popup, while leaving the lifetime total intact for perSurface().
        monitor.detach("window:0")
        assertEquals(0.0, monitor.ratePerSecond)
        assertEquals(3L, monitor.total)
        monitor.close()
    }

    @Test
    fun `close cancels the owned scope and is idempotent`() {
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        val scope = monitor.scopeForTests
        assertTrue(scope.isActive)
        monitor.close()
        assertFalse(scope.isActive)
        // Calling close again must be a safe no-op.
        monitor.close()
    }

    @Test
    fun `awaitRateBelow returns true immediately when no surfaces have been recorded`() =
        runBlocking {
            // Real time, not runTest's virtual time — the monitor's loop uses
            // TimeSource.Monotonic for elapsed-time accounting and `delay` for pacing; mixing
            // those with a virtual scheduler makes the quiet-period check fire on virtual time
            // while the elapsed-time check still uses wall clock, producing false timeouts.
            val monitor = RecompositionMonitor(windowDuration = 1.seconds)
            val under = monitor.awaitRateBelow(threshold = 1.0, timeout = 100.milliseconds)
            assertTrue(under)
            monitor.close()
        }

    @Test
    fun `awaitCompositionIdle returns true when there are no attached recomposers`() = runBlocking {
        // With nothing attached, "any pending work" is vacuously false and the quiet period
        // begins counting from t=0 — the wait must return true within timeout. runBlocking
        // (not runTest) so the wall-clock TimeSource the monitor uses internally lines up
        // with the coroutine `delay`s.
        val monitor = RecompositionMonitor(windowDuration = 1.seconds)
        val idle =
            monitor.awaitCompositionIdle(
                quietPeriod = 10.milliseconds,
                timeout = 500.milliseconds,
                pollInterval = 5.milliseconds,
            )
        assertTrue(idle)
        monitor.close()
    }
}
