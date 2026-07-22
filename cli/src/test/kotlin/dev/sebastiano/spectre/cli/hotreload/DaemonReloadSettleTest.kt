package dev.sebastiano.spectre.cli.hotreload

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.cli.daemon.DaemonErrorCode
import dev.sebastiano.spectre.cli.daemon.DaemonRequest
import dev.sebastiano.spectre.cli.daemon.DaemonResponse
import dev.sebastiano.spectre.cli.daemon.DaemonSessionRegistry
import dev.sebastiano.spectre.cli.daemon.TestDaemonSessionAutomator
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonReloadSettleTest {
    @Test
    fun `non-HR session fails closed immediately as hotReloadUnavailable`() {
        val registry =
            DaemonSessionRegistry(
                attachAutomator = { TestDaemonSessionAutomator() },
                hotReloadSessionFactory = { null },
            )
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(4242))).sessionId

        val started = System.nanoTime()
        val response =
            assertIs<DaemonResponse.Error>(
                registry.handle(
                    DaemonRequest.WaitForReloadSettled(sessionId = sessionId, timeoutMs = 30_000)
                )
            )
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertEquals(DaemonErrorCode.HotReloadUnavailable, response.code)
        assertEquals(ReloadSettleErrorCategory.HOT_RELOAD_UNAVAILABLE, response.category)
        assertTrue(
            elapsedMs < 2_000,
            "non-HR settle must fail closed immediately, took ${elapsedMs}ms",
        )
    }

    @Test
    fun `reload-aware session maps outcomes onto daemon error taxonomy`() {
        val outcomes =
            ConcurrentLinkedQueue(
                listOf(
                    ReloadSettleOutcome.ReloadFailed("boom"),
                    ReloadSettleOutcome.TimedOut,
                    ReloadSettleOutcome.Settled,
                )
            )
        val controllable = ControllableHotReloadCapability(outcomes)
        val registry =
            DaemonSessionRegistry(
                attachAutomator = { TestDaemonSessionAutomator() },
                hotReloadSessionFactory = { controllable },
            )
        val sessionId =
            assertIs<DaemonResponse.Attached>(registry.handle(DaemonRequest.Attach(7))).sessionId

        val failed =
            assertIs<DaemonResponse.Error>(
                registry.handle(DaemonRequest.WaitForReloadSettled(sessionId, timeoutMs = 5_000))
            )
        assertEquals(DaemonErrorCode.ReloadFailed, failed.code)
        assertEquals(ReloadSettleErrorCategory.RELOAD_FAILED, failed.category)
        assertTrue(failed.message.contains("boom"))

        val timedOut =
            assertIs<DaemonResponse.Error>(
                registry.handle(DaemonRequest.WaitForReloadSettled(sessionId, timeoutMs = 5_000))
            )
        assertEquals(DaemonErrorCode.Timeout, timedOut.code)
        assertEquals(ReloadSettleErrorCategory.TIMEOUT, timedOut.category)

        val settled =
            assertIs<DaemonResponse.Completed>(
                registry.handle(DaemonRequest.WaitForReloadSettled(sessionId, timeoutMs = 5_000))
            )
        assertEquals(sessionId, settled.sessionId)
    }
}

private class ControllableHotReloadCapability(
    private val outcomes: java.util.Queue<ReloadSettleOutcome>
) : HotReloadCapability {
    override fun waitForReloadSettled(timeoutMs: Long): ReloadSettleOutcome =
        outcomes.poll() ?: ReloadSettleOutcome.Unavailable

    override fun close() = Unit
}
