@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class AgentPlatformPreflightTest {
    @Test
    fun `Windows passes when AF_UNIX is supported`() {
        AgentPlatformPreflight.requireSupported(osName = "Windows 11", afUnixSupported = { true })
    }

    @Test
    fun `Windows without AF_UNIX fails with a dedicated unsupported platform exception`() {
        val ex =
            assertFailsWith<AttachPlatformUnsupportedException> {
                AgentPlatformPreflight.requireSupported(
                    osName = "Windows 10",
                    afUnixSupported = { false },
                )
            }
        assertEquals("Windows 10", ex.osName)
    }

    @Test
    fun `macOS and Linux pass without probing AF_UNIX`() {
        var probed = false
        val probe = {
            probed = true
            true
        }
        AgentPlatformPreflight.requireSupported(osName = "Mac OS X", afUnixSupported = probe)
        AgentPlatformPreflight.requireSupported(osName = "Linux", afUnixSupported = probe)
        assertFalse(probed, "non-Windows platforms must not probe AF_UNIX")
    }

    @Test
    fun `the real AF_UNIX probe passes on this supported host`() {
        // Every leg of the dev/CI matrix (Linux, macOS, Windows 10 1803+/Server 2019+) supports
        // native AF_UNIX, so the default probe against the real os.name must not throw.
        AgentPlatformPreflight.requireSupported()
    }
}
