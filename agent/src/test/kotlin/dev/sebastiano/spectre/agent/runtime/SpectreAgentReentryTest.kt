@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #557: an attacher killed without sending Detach leaves the agent bootstrapped on its old socket.
 * The next `agentmain` names a fresh socket path, and the agent has to rebind there instead of
 * ignoring the re-entry and leaving the attacher to time out.
 */
class SpectreAgentReentryTest {
    private val boundPath = Path.of("/tmp/sp-a-42-aaaaaaaa/agent.sock")

    @Test
    fun `re-entry naming a new socket path replaces the bound agent`() {
        val requested = Path.of("/tmp/sp-a-42-bbbbbbbb/agent.sock")

        assertTrue(SpectreAgent.replacesBoundAgent(boundPath, requested) { true })
    }

    @Test
    fun `re-entry on the bound socket path replaces it once its socket file is gone`() {
        assertTrue(SpectreAgent.replacesBoundAgent(boundPath, boundPath) { false })
    }

    @Test
    fun `re-entry on the live bound socket path keeps the bound agent`() {
        assertFalse(SpectreAgent.replacesBoundAgent(boundPath, boundPath) { true })
    }

    @Test
    fun `re-entry without a socket path keeps the bound agent`() {
        assertFalse(SpectreAgent.replacesBoundAgent(boundPath, requestedUdsPath = null) { false })
    }
}
