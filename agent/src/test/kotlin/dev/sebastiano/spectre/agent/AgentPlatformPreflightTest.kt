@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentPlatformPreflightTest {
    @Test
    fun `Windows fails with a dedicated unsupported platform exception`() {
        val ex =
            assertFailsWith<AttachPlatformUnsupportedException> {
                AgentPlatformPreflight.requireSupported(osName = "Windows 11")
            }

        assertEquals("Windows 11", ex.osName)
    }

    @Test
    fun `macOS and Linux pass the agent platform preflight`() {
        AgentPlatformPreflight.requireSupported(osName = "Mac OS X")
        AgentPlatformPreflight.requireSupported(osName = "Linux")
    }
}
