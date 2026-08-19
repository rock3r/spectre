@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import dev.sebastiano.spectre.agent.transport.MAX_FRAME_BYTES_CEILING
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * A budget the target cannot apply must fail at the caller, not in the injected JVM. The agent logs
 * and ignores a bad value so a tuning mistake cannot break the attach, which means an unvalidated
 * option would let `attach()` report success while the target silently kept its own budget and
 * later rejected captures the caller sized for.
 */
class AttachOptionsTest {

    @Test
    fun `a budget above the read ceiling is rejected at construction`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                AttachOptions(maxFrameBytes = MAX_FRAME_BYTES_CEILING + 1)
            }

        assertTrue(failure.message.orEmpty().contains("ceiling", ignoreCase = true), "$failure")
    }

    @Test
    fun `a non-positive budget is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { AttachOptions(maxFrameBytes = 0) }
        assertFailsWith<IllegalArgumentException> { AttachOptions(maxFrameBytes = -1) }
    }

    @Test
    fun `a budget too small for protocol frames is rejected at construction`() {
        assertFailsWith<IllegalArgumentException> { AttachOptions(maxFrameBytes = 1) }
    }

    @Test
    fun `a usable budget and the unset default are both accepted`() {
        AttachOptions(maxFrameBytes = MAX_FRAME_BYTES_CEILING)
        AttachOptions(maxFrameBytes = null)
    }
}
