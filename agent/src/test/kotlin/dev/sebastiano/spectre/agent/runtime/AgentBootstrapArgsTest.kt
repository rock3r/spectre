@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.AttachInputCoordination
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
    fun `a bare relative path that starts with uds= is not mistaken for structured args`() {
        // Both are legal POSIX names. Everything Spectre renders carries at least one more field,
        // so requiring the separator keeps the pathological bare path working.
        val parsed = AgentBootstrapArgs.parse("uds=agent.sock")

        assertEquals("uds=agent.sock", parsed.udsPath)
        assertNull(parsed.maxFrameBytes)
    }

    @Test
    fun `structured args carry the uds path and the frame budget`() {
        val parsed = AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=134217728")

        assertEquals("/tmp/sp-a-1/agent.sock", parsed.udsPath)
        assertEquals(134217728, parsed.maxFrameBytes)
    }

    @Test
    fun `structured args tolerate a missing budget`() {
        val parsed = AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock,futureKnob=7")

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
    fun `always renders the structured form, default budget included`() {
        // The target may carry a SPECTRE_MAX_FRAME_BYTES of its own; omitting the default would
        // let that win and leave the daemon and its injected JVM disagreeing.
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

    @Test
    fun `a uds path containing the delimiters survives the round trip`() {
        // AttachOptions lets a caller pick the socket path, and a comma is legal in one. Without
        // escaping, parse() would truncate it and the target would bind a different socket than
        // the attacher waits for.
        val awkward = "/tmp/spectre,a=b/%weird/agent.sock"

        val parsed = AgentBootstrapArgs.parse(AgentBootstrapArgs.render(awkward, 4096))

        assertEquals(awkward, parsed.udsPath)
        assertEquals(4096, parsed.maxFrameBytes)
    }

    @Test
    fun `an escaped percent cannot forge a delimiter`() {
        // "%252C" must decode to the literal "%2C", not to a comma that splits the path.
        val awkward = "/tmp/sp-a/%2C/agent.sock"

        val parsed = AgentBootstrapArgs.parse(AgentBootstrapArgs.render(awkward, 4096))

        assertEquals(awkward, parsed.udsPath)
    }

    // ---- input coordination (#472) ----
    //
    // The injected runtime cannot read the attacher's system properties any more than it can read
    // its environment, so the attacher's coordination decision has to ride in `agentArgs` too. The
    // *absence* of that field must mean Required: an older attacher, a hand-written `-javaagent:`
    // line, and a corrupted value all have to land on the coordinated behaviour, not off it.

    @Test
    fun `a missing coordination field means coordination is required`() {
        val parsed = AgentBootstrapArgs.parse("uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=1024")

        assertEquals(AttachInputCoordination.Required, parsed.inputCoordination)
    }

    @Test
    fun `a bare path means coordination is required`() {
        val parsed = AgentBootstrapArgs.parse("/tmp/sp-a-123-abcd/agent.sock")

        assertEquals(AttachInputCoordination.Required, parsed.inputCoordination)
    }

    @Test
    fun `null args mean coordination is required`() {
        assertEquals(
            AttachInputCoordination.Required,
            AgentBootstrapArgs.parse(null).inputCoordination,
        )
    }

    @Test
    fun `the two-argument render keeps the coordinated behaviour it always had`() {
        val rendered =
            AgentBootstrapArgs.render(udsPath = "/tmp/sp-a-1/agent.sock", maxFrameBytes = 1024)

        assertEquals("uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=1024", rendered)
        assertEquals(
            AttachInputCoordination.Required,
            AgentBootstrapArgs.parse(rendered).inputCoordination,
        )
    }

    @Test
    fun `a disabled attach carries the opt-out to the target`() {
        val rendered =
            AgentBootstrapArgs.render(
                udsPath = "/tmp/sp-a-1/agent.sock",
                maxFrameBytes = 1024,
                inputCoordination = AttachInputCoordination.Disabled,
            )

        assertEquals(
            "uds=/tmp/sp-a-1/agent.sock,maxFrameBytes=1024,inputCoordination=disabled",
            rendered,
        )
        assertEquals(
            AttachInputCoordination.Disabled,
            AgentBootstrapArgs.parse(rendered).inputCoordination,
        )
    }

    @Test
    fun `a required attach renders the field explicitly and round-trips`() {
        val rendered =
            AgentBootstrapArgs.render(
                udsPath = "/tmp/sp-a-1/agent.sock",
                maxFrameBytes = 1024,
                inputCoordination = AttachInputCoordination.Required,
            )

        assertEquals(
            AttachInputCoordination.Required,
            AgentBootstrapArgs.parse(rendered).inputCoordination,
        )
    }

    @Test
    fun `an unrecognised coordination value stays coordinated`() {
        // Same safe direction as the property parser: a corrupted or truncated field must not be
        // the thing that quietly stops policing the desktop.
        val parsed =
            AgentBootstrapArgs.parse(
                "uds=/tmp/sp-a-1/agent.sock,inputCoordination=true,maxFrameBytes=1"
            )

        assertEquals(AttachInputCoordination.Required, parsed.inputCoordination)
    }
}
