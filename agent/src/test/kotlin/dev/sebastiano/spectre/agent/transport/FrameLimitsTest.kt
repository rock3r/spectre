@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The frame budget is the knob that decides whether a HiDPI fullscreen still fits on the wire. #204
 * asked for it to be deterministic and documented rather than a round number nobody picked: the
 * default holds a worst-case 4K desktop, and the override raises it for larger rigs.
 */
class FrameLimitsTest {

    @Test
    fun `default budget holds a worst-case 4K fullscreen still`() {
        // A 3840x2160 screenshot is 8.3MP. PNG worst case is roughly raw bytes-per-pixel, so
        // budget for 3 bytes per pixel (24-bit sRGB) with no compression win at all.
        val worstCase4K = 3840L * 2160L * 3L
        assertTrue(
            DEFAULT_MAX_FRAME_BYTES >= worstCase4K,
            "default $DEFAULT_MAX_FRAME_BYTES must hold an incompressible 4K still ($worstCase4K)",
        )
    }

    @Test
    fun `read ceiling stays above the default so a configured writer is always readable`() {
        assertTrue(MAX_FRAME_BYTES_CEILING > DEFAULT_MAX_FRAME_BYTES)
    }

    @Test
    fun `parses plain byte counts`() {
        assertEquals(1024, FrameLimits.parseMaxFrameBytes("1024"))
        assertEquals(64 * 1024 * 1024, FrameLimits.parseMaxFrameBytes(" 67108864 "))
    }

    @Test
    fun `parses binary size suffixes`() {
        assertEquals(64 * 1024 * 1024, FrameLimits.parseMaxFrameBytes("64M"))
        assertEquals(64 * 1024 * 1024, FrameLimits.parseMaxFrameBytes("64MiB"))
        assertEquals(128 * 1024, FrameLimits.parseMaxFrameBytes("128k"))
        assertEquals(1024 * 1024 * 1024, FrameLimits.parseMaxFrameBytes("1G"))
    }

    @Test
    fun `rejects values that are not a positive size`() {
        assertNull(FrameLimits.parseMaxFrameBytes(null))
        assertNull(FrameLimits.parseMaxFrameBytes(""))
        assertNull(FrameLimits.parseMaxFrameBytes("zero"))
        assertNull(FrameLimits.parseMaxFrameBytes("-5"))
        assertNull(FrameLimits.parseMaxFrameBytes("0"))
        assertNull(FrameLimits.parseMaxFrameBytes("12MB"))
    }

    @Test
    fun `rejects sizes that overflow the ceiling instead of wrapping`() {
        // 2GiB does not fit a signed Int; the parser must not wrap into a small or negative
        // budget, which would silently shrink the limit instead of raising it.
        assertNull(FrameLimits.parseMaxFrameBytes("2G"))
        assertNull(FrameLimits.parseMaxFrameBytes("8G"))
        assertNull(FrameLimits.parseMaxFrameBytes("999999999999"))
    }

    @Test
    fun `configure refuses a budget above the read ceiling`() {
        assertFailsWith<IllegalArgumentException> {
                FrameLimits.configure(MAX_FRAME_BYTES_CEILING + 1)
            }
            .also { assertTrue(it.message.orEmpty().contains("ceiling", ignoreCase = true)) }
    }

    @Test
    fun `nothing is requested until something asks`() {
        val restore = FrameLimits.maxFrameBytes
        try {
            FrameLimits.resetToEnvironment()
            assertNull(
                FrameLimits.requestedMaxFrameBytes,
                "an unset environment must not look like an explicit request",
            )
            assertEquals(DEFAULT_MAX_FRAME_BYTES, FrameLimits.maxFrameBytes)
        } finally {
            FrameLimits.configure(restore)
        }
    }

    @Test
    fun `a request equal to the default is still a request`() {
        val restore = FrameLimits.maxFrameBytes
        try {
            FrameLimits.configure(DEFAULT_MAX_FRAME_BYTES)
            assertEquals(
                DEFAULT_MAX_FRAME_BYTES,
                FrameLimits.requestedMaxFrameBytes,
                "intent must not be inferred from the value matching the default",
            )
        } finally {
            FrameLimits.configure(restore)
        }
    }

    @Test
    fun `an environment override counts as a request`() {
        assertEquals(
            32 * 1024 * 1024,
            FrameLimits.resolveRequest { name ->
                if (name == FrameLimits.ENV_VAR) "32MiB" else null
            },
        )
        assertNull(FrameLimits.resolveRequest { null })
        assertNull(
            FrameLimits.resolveRequest { name ->
                if (name == FrameLimits.ENV_VAR) "banana" else null
            }
        )
    }

    @Test
    fun `configure applies and restores a budget`() {
        val original = FrameLimits.maxFrameBytes
        try {
            FrameLimits.configure(1234)
            assertEquals(1234, FrameLimits.maxFrameBytes)
        } finally {
            FrameLimits.configure(original)
        }
        assertEquals(original, FrameLimits.maxFrameBytes)
    }

    @Test
    fun `environment override wins over the default`() {
        assertEquals(
            32 * 1024 * 1024,
            FrameLimits.resolveBudget { name -> if (name == FrameLimits.ENV_VAR) "32MiB" else null },
        )
    }

    @Test
    fun `unparseable environment override falls back to the default`() {
        assertEquals(
            DEFAULT_MAX_FRAME_BYTES,
            FrameLimits.resolveBudget { name ->
                if (name == FrameLimits.ENV_VAR) "banana" else null
            },
        )
    }

    @Test
    fun `environment override is clamped to the read ceiling`() {
        assertEquals(
            MAX_FRAME_BYTES_CEILING,
            FrameLimits.resolveBudget { name -> if (name == FrameLimits.ENV_VAR) "1G" else null },
        )
    }
}
