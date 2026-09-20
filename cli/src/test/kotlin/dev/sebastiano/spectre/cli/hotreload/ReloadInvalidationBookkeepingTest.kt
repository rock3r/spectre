package dev.sebastiano.spectre.cli.hotreload

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReloadInvalidationBookkeepingTest {

    @Test
    fun `matching result then UIRendered fires once`() {
        val tracker = ReloadInvalidationBookkeeping()
        assertFalse(
            tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-1", isSuccess = true))
        )
        assertTrue(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))
    }

    @Test
    fun `rebroadcast of the same request id does not fire again`() {
        val tracker = ReloadInvalidationBookkeeping()
        tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-1", isSuccess = true))
        assertTrue(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))

        assertFalse(
            tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-1", isSuccess = true))
        )
        assertFalse(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))
    }

    @Test
    fun `a later distinct request id fires again`() {
        val tracker = ReloadInvalidationBookkeeping()
        tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-1", isSuccess = true))
        assertTrue(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))

        tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-2", isSuccess = true))
        assertTrue(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-2")))
    }

    @Test
    fun `reconnect-style clearPending does not re-fire the same request id`() {
        val tracker = ReloadInvalidationBookkeeping()
        tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-1", isSuccess = true))
        assertTrue(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))

        tracker.clearPending()

        assertFalse(
            tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-1", isSuccess = true))
        )
        assertFalse(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))

        tracker.onEvent(ReloadLifecycleEvent.ReloadClassesResult("req-2", isSuccess = true))
        assertTrue(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-2")))
    }

    @Test
    fun `failed result does not arm invalidation`() {
        val tracker = ReloadInvalidationBookkeeping()
        assertFalse(
            tracker.onEvent(
                ReloadLifecycleEvent.ReloadClassesResult(
                    "req-1",
                    isSuccess = false,
                    errorMessage = "boom",
                )
            )
        )
        assertFalse(tracker.onEvent(ReloadLifecycleEvent.UIRendered("req-1")))
    }
}
