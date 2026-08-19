package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits

/**
 * The `agentArgs` string handed to [SpectreAgent.premain] / [SpectreAgent.agentmain].
 *
 * Two forms are accepted:
 * - **bare** — the UDS path on its own, e.g. `/tmp/sp-a-123-abcd/agent.sock`. This is what a
 *   hand-written `-javaagent:spectre-agent-runtime.jar=<path>` produces, so it stays supported.
 * - **structured** — `uds=<path>[,key=value]*`, used when the attacher has something else to pass.
 *   Today the only extra key is `maxFrameBytes`: an injected agent cannot read the daemon's
 *   environment, and it is the process that writes the bulky screenshot frames, so its budget has
 *   to arrive here.
 *
 * The structured form is recognised by the `uds=` prefix, which a UDS path never starts with.
 * Unknown keys and unparseable values are ignored rather than failing the attach — a newer daemon
 * injecting an older runtime should still get a working session, just without the newer knob.
 */
@ExperimentalSpectreAgentApi
public object AgentBootstrapArgs {

    /** Parsed view of an `agentArgs` string. */
    public data class Parsed(public val udsPath: String?, public val maxFrameBytes: Int?)

    /** Parses [agentArgs]; a null/blank string means diagnostic mode (no UDS, no IPC server). */
    public fun parse(agentArgs: String?): Parsed {
        val text = agentArgs?.trim()?.takeIf { it.isNotEmpty() } ?: return Parsed(null, null)
        if (!text.startsWith(STRUCTURED_PREFIX)) return Parsed(text, null)
        val entries =
            text.split(SEPARATOR).mapNotNull { entry ->
                val key = entry.substringBefore(KEY_VALUE, missingDelimiterValue = "")
                if (key.isEmpty()) null else key to entry.substringAfter(KEY_VALUE)
            }
        val fields = entries.toMap()
        return Parsed(
            udsPath = fields[UDS_KEY]?.takeIf { it.isNotEmpty() },
            maxFrameBytes = FrameLimits.parseMaxFrameBytes(fields[MAX_FRAME_BYTES_KEY]),
        )
    }

    /** Renders [udsPath], using the bare form unless [maxFrameBytes] needs carrying. */
    public fun render(udsPath: String, maxFrameBytes: Int?): String =
        if (maxFrameBytes == null) udsPath
        else "$STRUCTURED_PREFIX$udsPath$SEPARATOR$MAX_FRAME_BYTES_KEY$KEY_VALUE$maxFrameBytes"

    private const val UDS_KEY: String = "uds"
    private const val MAX_FRAME_BYTES_KEY: String = "maxFrameBytes"
    private const val KEY_VALUE: String = "="
    private const val SEPARATOR: String = ","
    private const val STRUCTURED_PREFIX: String = "$UDS_KEY$KEY_VALUE"
}
