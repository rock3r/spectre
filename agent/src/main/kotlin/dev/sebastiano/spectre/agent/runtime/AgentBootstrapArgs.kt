package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits

/**
 * The `agentArgs` string handed to [SpectreAgent.premain] / [SpectreAgent.agentmain].
 *
 * Two forms are **accepted**, and [render] always emits the second:
 * - **bare** — the UDS path on its own, e.g. `/tmp/sp-a-123-abcd/agent.sock`. This is what a
 *   hand-written `-javaagent:spectre-agent-runtime.jar=<path>` produces, so it stays supported.
 * - **structured** — `uds=<path>[,key=value]*`. An injected agent cannot read the attacher's
 *   environment, and it is the process that writes the bulky screenshot frames, so its budget has
 *   to arrive here — including when that budget is the default, since the target may carry a
 *   `SPECTRE_MAX_FRAME_BYTES` of its own that must not silently win.
 *
 * The structured form is recognised by a `uds=` prefix *and* at least one further `,`-separated
 * field, which everything [render] emits carries. A bare path is therefore only misread if it both
 * starts with `uds=` and contains a comma (`uds=agent,1.sock`). Every prefix-based discriminator
 * has some such filename; this one is documented rather than chased, since Spectre only emits the
 * structured form and the bare form exists for hand-written `-javaagent:` lines. Values are
 * percent-escaped, so a caller-supplied UDS path containing `,` or `=` survives the round trip
 * instead of truncating and leaving the target bound to a different socket. Unknown keys and
 * unparseable values are ignored rather than failing the attach, so a runtime that understands this
 * format but not a newer key still gets a working session. That tolerance does not extend to a
 * runtime predating the format itself: it would read the whole string as a socket path and bind the
 * wrong one. Pairing a mismatched runtime JAR is only reachable through
 * `AttachOptions.agentJarPath` or the runtime-jar system property, and the agent protocol's
 * exact-match handshake rejects the pairing once it binds.
 */
@ExperimentalSpectreAgentApi
public object AgentBootstrapArgs {

    /** Parsed view of an `agentArgs` string. */
    public data class Parsed(public val udsPath: String?, public val maxFrameBytes: Int?)

    /** Parses [agentArgs]; a null/blank string means diagnostic mode (no UDS, no IPC server). */
    public fun parse(agentArgs: String?): Parsed {
        val text = agentArgs?.trim()?.takeIf { it.isNotEmpty() } ?: return Parsed(null, null)
        // Prefix *and* a separator: `uds=agent.sock` is a legal relative path, and everything
        // render() emits carries at least one more field, so requiring both keeps that path bare
        // instead of silently binding `agent.sock` while the attacher waits on `uds=agent.sock`.
        if (!text.startsWith(STRUCTURED_PREFIX) || !text.contains(SEPARATOR)) {
            return Parsed(text, null)
        }
        val entries =
            text.split(SEPARATOR).mapNotNull { entry ->
                val key = entry.substringBefore(KEY_VALUE, missingDelimiterValue = "")
                if (key.isEmpty()) null else key to entry.substringAfter(KEY_VALUE)
            }
        val fields = entries.toMap()
        return Parsed(
            udsPath = fields[UDS_KEY]?.let(::decodeValue)?.takeIf { it.isNotEmpty() },
            maxFrameBytes = FrameLimits.parseMaxFrameBytes(fields[MAX_FRAME_BYTES_KEY]),
        )
    }

    /** Renders [udsPath] and [maxFrameBytes] as a structured, escaped argument string. */
    public fun render(udsPath: String, maxFrameBytes: Int): String =
        "$STRUCTURED_PREFIX${encodeValue(udsPath)}" +
            "$SEPARATOR$MAX_FRAME_BYTES_KEY$KEY_VALUE$maxFrameBytes"

    /**
     * Escapes the delimiters this format reserves. `%` goes first so its escape is not re-escaped.
     */
    private fun encodeValue(raw: String): String =
        raw.replace("%", "%25").replace(SEPARATOR, "%2C").replace(KEY_VALUE, "%3D")

    /**
     * Reverses [encodeValue]. `%25` goes last so an escaped `%` cannot produce a fake delimiter.
     */
    private fun decodeValue(raw: String): String =
        raw.replace("%2C", SEPARATOR).replace("%3D", KEY_VALUE).replace("%25", "%")

    private const val UDS_KEY: String = "uds"
    private const val MAX_FRAME_BYTES_KEY: String = "maxFrameBytes"
    private const val KEY_VALUE: String = "="
    private const val SEPARATOR: String = ","
    private const val STRUCTURED_PREFIX: String = "$UDS_KEY$KEY_VALUE"
}
