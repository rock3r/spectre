package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.DEFAULT_MAX_FRAME_BYTES
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A running daemon keeps the frame budget it booted with, so `--max-frame-bytes` on a later
 * invocation cannot reach it. Silently continuing would apply the requested budget to one hop of
 * three; the handshake refuses instead and says how to make it stick.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonFrameBudgetHandshakeTest {

    @Test
    fun `a matching budget is accepted`() {
        assertNull(frameBudgetMismatchFailure(requested = 128 * MIB, daemonBudget = 128 * MIB))
    }

    @Test
    fun `an unconfigured client accepts whatever the daemon booted with`() {
        // Nothing was asked for, and readers accept frames up to the ceiling regardless of their
        // own budget, so a daemon on a larger budget is not a problem worth failing over.
        assertNull(
            frameBudgetMismatchFailure(
                requested = DEFAULT_MAX_FRAME_BYTES,
                daemonBudget = 256 * MIB,
            )
        )
        assertNull(
            frameBudgetMismatchFailure(requested = DEFAULT_MAX_FRAME_BYTES, daemonBudget = null)
        )
    }

    @Test
    fun `a budget the running daemon cannot honour is refused`() {
        val failure =
            assertNotNull(
                frameBudgetMismatchFailure(requested = 256 * MIB, daemonBudget = 64 * MIB)
            )

        assertTrue(failure.contains("--max-frame-bytes"), failure)
        assertTrue(failure.contains("256MiB"), "should name what was asked for: $failure")
        assertTrue(failure.contains("64MiB"), "should name what the daemon runs: $failure")
        assertTrue(
            failure.contains("spectre daemon kill"),
            "should say how to make the budget stick: $failure",
        )
    }

    @Test
    fun `a daemon too old to report its budget is refused rather than assumed`() {
        val failure =
            assertNotNull(frameBudgetMismatchFailure(requested = 256 * MIB, daemonBudget = null))

        assertTrue(failure.contains("spectre daemon kill"), failure)
    }

    @Test
    fun `sizes are rendered in the units the flag accepts`() {
        val failure =
            assertNotNull(
                frameBudgetMismatchFailure(requested = 128 * MIB, daemonBudget = 100 * 1024)
            )

        assertTrue(failure.contains("128MiB"), failure)
        assertTrue(failure.contains("100KiB"), "sub-MiB budgets should not round to 0MiB: $failure")
    }

    private companion object {
        const val MIB: Int = 1024 * 1024
    }
}
