package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.Framing
import dev.sebastiano.spectre.agent.transport.MAX_FRAME_BYTES
import java.io.InputStream
import java.io.OutputStream

@OptIn(ExperimentalSpectreAgentApi::class)
/** Length-prefixed binary framing for the Spectre CLI/daemon wire protocol. */
public object DaemonFraming {
    /** Default upper bound on a single daemon frame payload. */
    public const val MaxFrameBytes: Int = MAX_FRAME_BYTES

    /** Write one `[4-byte big-endian length][payload]` frame and flush [output]. */
    @Throws(java.io.IOException::class)
    public fun writeFrame(output: OutputStream, payload: ByteArray): Unit =
        Framing.writeFrame(output, payload)

    /**
     * Read one frame payload, return `null` on clean EOF before a header starts, and fail on
     * truncated frames or invalid lengths.
     */
    @Throws(java.io.IOException::class)
    public fun readFrame(input: InputStream): ByteArray? = Framing.readFrame(input)
}
