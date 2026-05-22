@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Verifies that [WindowTracker.trackedWindows] is exposed as a [StateFlow] and follows
 * `StateFlow`'s distinctUntilChanged contract — successive refreshes that produce equal lists emit
 * only the initial value, so downstream subscribers (e.g. `RecompositionMonitor`) can reconcile on
 * change events without false positives.
 *
 * No live AWT required: [WindowTracker.empty] runs entirely synchronously and always produces an
 * empty list, which is the simplest distinct-emission scenario.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowTrackerStateFlowTest {

    @Test
    fun `trackedWindows is exposed as a StateFlow with an initial empty value`() = runTest {
        val tracker = WindowTracker.empty()
        val flow: StateFlow<List<TrackedWindow>> = tracker.trackedWindows
        assertEquals(emptyList(), flow.value)
        assertEquals(emptyList(), flow.first())
    }

    @Test
    fun `repeated refresh with no actual change emits only the initial value`() = runTest {
        val tracker = WindowTracker.empty()
        val emissions = mutableListOf<List<TrackedWindow>>()
        val collector =
            launch(UnconfinedTestDispatcher(testScheduler)) {
                tracker.trackedWindows.take(2).toList(emissions)
            }
        runCurrent()
        tracker.refresh()
        tracker.refresh()
        tracker.refresh()
        runCurrent()
        collector.cancel()
        assertEquals(
            listOf(emptyList<TrackedWindow>()),
            emissions,
            "Expected only the initial empty emission, got $emissions",
        )
    }
}
