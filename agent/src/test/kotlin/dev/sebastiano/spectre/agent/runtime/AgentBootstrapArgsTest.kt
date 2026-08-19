@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The injected agent cannot see the daemon's environment, so the frame budget has to ride along in
 * `agentArgs`. Hand-written `-javaagent:spectre-agent-runtime.jar=/path/to.sock` lines must keep
 * working, so the bare-path form stays valid.
 */
class AgentBootstrapArgsTest {

    @Test
    fun `a bare path is still the UDS path`() {
        val parsed = AgentBootstrapArgs.parse("/tmp/sp-a-123-abcd/agent.sock")

        assertEquals("/tmp/sp-a-123-abcd/agent.sock", parsed.udsPath)
        assertNull(parsed.maxFrameBytes, "bare form carries no budget; the default applies")
    }

    @Test
    fun `null and blank args mean diagnostic mode`() {
        assertNull(AgentBootstrapArgs.parse(null).udsPath)
        assertNull(AgentBootstrapArgs.parse("   ").udsPath)
    }

    @Test
    fun `structured args carry the uds path and the frame budget`() {
        val parsed = AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=134217728")

        assertEquals("/tmp/sp-a-1/agent.sock", parsed.udsPath)
        assertEquals(134217728, parsed.maxFrameBytes)
    }

    @Test
    fun `structured args tolerate a missing budget`() {
        val parsed = AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock")

        assertEquals("/tmp/sp-a-1/agent.sock", parsed.udsPath)
        assertNull(parsed.maxFrameBytes)
    }

    @Test
    fun `an unparseable budget is ignored rather than failing the attach`() {
        val parsed = AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=banana")

        assertEquals("/tmp/sp-a-1/agent.sock", parsed.udsPath)
        assertNull(parsed.maxFrameBytes)
    }

    @Test
    fun `unknown keys are ignored so newer daemons can attach older runtimes`() {
        val parsed =
            AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock,futureKnob=7,maxFrameBytes=1024")

        assertEquals("/tmp/sp-a-1/agent.sock", parsed.udsPath)
        assertEquals(1024, parsed.maxFrameBytes)
    }

    @Test
    fun `renders the bare form when no budget override is in play`() {
        assertEquals(
            "/tmp/sp-a-1/agent.sock",
            AgentBootstrapArgs.render(udsPath = "/tmp/sp-a-1/agent.sock", maxFrameBytes = null),
        )
    }

    @Test
    fun `renders the structured form when a budget is set`() {
        assertEquals(
            "uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=1024",
            AgentBootstrapArgs.render(udsPath = "/tmp/sp-a-1/agent.sock", maxFrameBytes = 1024),
        )
    }

    @Test
    fun `render and parse round-trip`() {
        val rendered =
            AgentBootstrapArgs.render(udsPath = "/tmp/sp-a-9/agent.sock", maxFrameBytes = 65536)
        val parsed = AgentBootstrapArgs.parse(rendered)

        assertEquals("/tmp/sp-a-9/agent.sock", parsed.udsPath)
        assertEquals(65536, parsed.maxFrameBytes)
    }
}
