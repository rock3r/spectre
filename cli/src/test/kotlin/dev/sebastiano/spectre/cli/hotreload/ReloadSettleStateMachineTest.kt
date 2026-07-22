package dev.sebastiano.spectre.cli.hotreload

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReloadSettleStateMachineTest {
    @Test
    fun `full chain settles only after matching UIRendered and Ack`() {
        val machine = ReloadSettleStateMachine()
        assertNull(machine.onEvent(ReloadLifecycleEvent.ReloadClassesRequest("req-1")))
        assertNull(
            machine.onEvent(
                ReloadLifecycleEvent.ReloadClassesResult(
                    reloadRequestId = "req-1",
                    isSuccess = true,
                )
            )
        )
        // Initial render (null id) must not complete the chain.
        assertNull(machine.onEvent(ReloadLifecycleEvent.UIRendered(reloadRequestId = null)))
        // Wrong request id must not complete the chain.
        assertNull(machine.onEvent(ReloadLifecycleEvent.UIRendered(reloadRequestId = "other")))
        assertNull(machine.onEvent(ReloadLifecycleEvent.UIRendered(reloadRequestId = "req-1")))
        assertTrue(machine.needsPing())
        machine.beginPingDrain("ping-1")
        // Unrelated ack ignored.
        assertNull(machine.onEvent(ReloadLifecycleEvent.Ack("someone-else")))
        val outcome = machine.onEvent(ReloadLifecycleEvent.Ack("ping-1"))
        assertEquals(ReloadSettleOutcome.Settled, outcome)
    }

    @Test
    fun `reload failure surfaces error message and does not wait for UIRendered`() {
        val machine = ReloadSettleStateMachine()
        machine.onEvent(ReloadLifecycleEvent.ReloadClassesRequest("req-2"))
        val outcome =
            machine.onEvent(
                ReloadLifecycleEvent.ReloadClassesResult(
                    reloadRequestId = "req-2",
                    isSuccess = false,
                    errorMessage = "class redefine exploded",
                )
            )
        val failed = assertIs<ReloadSettleOutcome.ReloadFailed>(outcome)
        assertEquals("class redefine exploded", failed.errorMessage)
        // Further events are ignored once terminal.
        assertNull(machine.onEvent(ReloadLifecycleEvent.UIRendered("req-2")))
    }

    @Test
    fun `mismatched result id is ignored until matching result arrives`() {
        val machine = ReloadSettleStateMachine()
        machine.onEvent(ReloadLifecycleEvent.ReloadClassesRequest("want"))
        assertNull(
            machine.onEvent(
                ReloadLifecycleEvent.ReloadClassesResult(
                    reloadRequestId = "stale",
                    isSuccess = true,
                )
            )
        )
        assertNull(
            machine.onEvent(
                ReloadLifecycleEvent.ReloadClassesResult(reloadRequestId = "want", isSuccess = true)
            )
        )
        assertEquals("AwaitUiRendered", machine.currentPhase)
    }

    @Test
    fun `timeout before settle yields TimedOut`() {
        val machine = ReloadSettleStateMachine()
        machine.onEvent(ReloadLifecycleEvent.ReloadClassesRequest("req"))
        assertEquals(ReloadSettleOutcome.TimedOut, machine.onTimeout())
    }

    @Test
    fun `taxonomy wire names match issue contract`() {
        assertEquals(
            "hotReloadUnavailable",
            ReloadSettleErrorCategory.forOutcome(ReloadSettleOutcome.Unavailable),
        )
        assertEquals(
            "reloadFailed",
            ReloadSettleErrorCategory.forOutcome(ReloadSettleOutcome.ReloadFailed("x")),
        )
        assertEquals("timeout", ReloadSettleErrorCategory.forOutcome(ReloadSettleOutcome.TimedOut))
        assertEquals(
            "cancelled",
            ReloadSettleErrorCategory.forOutcome(ReloadSettleOutcome.Cancelled),
        )
        assertNull(ReloadSettleErrorCategory.forOutcome(ReloadSettleOutcome.Settled))
    }
}
