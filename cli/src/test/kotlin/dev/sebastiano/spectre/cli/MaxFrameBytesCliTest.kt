package dev.sebastiano.spectre.cli

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `--max-frame-bytes` is the escape hatch for multi-monitor HiDPI fullscreen stills, which can
 * encode past the default budget. It must apply before any request goes out, and a typo must fail
 * the invocation rather than silently leaving the default in place.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
class MaxFrameBytesCliTest {

    private val original = FrameLimits.maxFrameBytes

    @AfterTest fun restore() = FrameLimits.resetToEnvironment()

    @Test
    fun `applies the budget before the command runs`() {
        var budgetAtRequest = 0
        val cli =
            SpectreCli(
                request = {
                    budgetAtRequest = FrameLimits.maxFrameBytes
                    error("stop after observing the budget")
                },
                output = StringBuilder(),
                errorOutput = StringBuilder(),
            )

        runCatching { cli.run(listOf("--max-frame-bytes", "128MiB", "windows", "session-1")) }

        assertEquals(
            128 * 1024 * 1024,
            budgetAtRequest,
            "the budget must be in effect by the time a request is written",
        )
    }

    @Test
    fun `each invocation resolves its own budget`() {
        // SpectreCli.run is reusable, so a --max-frame-bytes from one call must not leave the
        // next one silently requesting a budget it never asked for — which would then either fail
        // the daemon handshake or boot a daemon on a value the user did not choose.
        val budgets = mutableListOf<Int?>()
        val cli =
            SpectreCli(
                request = {
                    budgets += FrameLimits.requestedMaxFrameBytes
                    error("stop after observing the budget")
                },
                output = StringBuilder(),
                errorOutput = StringBuilder(),
            )

        runCatching { cli.run(listOf("--max-frame-bytes", "128MiB", "windows", "session-1")) }
        runCatching { cli.run(listOf("windows", "session-1")) }

        assertEquals(listOf<Int?>(128 * 1024 * 1024, null), budgets)
    }

    @Test
    fun `rejects a size it cannot parse instead of falling back`() {
        val err = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("must not reach the daemon") },
                output = StringBuilder(),
                errorOutput = err,
            )

        val code = cli.run(listOf("--max-frame-bytes", "banana", "windows", "session-1"))

        assertTrue(code != 0, "a bad size must fail the invocation")
        assertTrue(
            err.toString().contains("max-frame-bytes"),
            "error should name the offending option: $err",
        )
        assertEquals(original, FrameLimits.maxFrameBytes, "a rejected value must not be applied")
        assertNull(
            FrameLimits.requestedMaxFrameBytes,
            "a rejected value must not count as a request",
        )
    }

    @Test
    fun `rejects a size above the read ceiling`() {
        val err = StringBuilder()
        val cli =
            SpectreCli(
                request = { error("must not reach the daemon") },
                output = StringBuilder(),
                errorOutput = err,
            )

        val code = cli.run(listOf("--max-frame-bytes", "1G", "windows", "session-1"))

        assertTrue(code != 0, "a budget readers would refuse must fail the invocation")
        assertTrue(
            err.toString().contains("ceiling", ignoreCase = true),
            "error should explain the ceiling: $err",
        )
    }
}
