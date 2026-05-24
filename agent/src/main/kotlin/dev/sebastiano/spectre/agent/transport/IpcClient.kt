package dev.sebastiano.spectre.agent.transport

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/**
 * Client-side IPC endpoint: opens a connection to the agent's Unix Domain Socket at [udsPath] and
 * synchronously sends [AgentRequest]s with [send].
 *
 * Single-shot send/receive: each [send] writes one framed CBOR request and reads the next framed
 * response. The wire protocol is strictly request/response in v1 (no streaming), so this is safe to
 * use without explicit pipelining.
 *
 * Not thread-safe — callers that need concurrent automator access should synchronise externally.
 * The `AttachedAutomator` wrapper exposes only serial operations.
 */
internal class IpcClient @Throws(IOException::class) constructor(udsPath: Path) : AutoCloseable {
    private val channel: SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).apply {
            connect(UnixDomainSocketAddress.of(udsPath))
        }
    private val input: InputStream = Channels.newInputStream(channel)
    private val output: OutputStream = Channels.newOutputStream(channel)

    /**
     * Sends [request], blocks until the matching response arrives, returns it. Throws [IOException]
     * if the channel closes mid-exchange or the wire protocol is violated.
     */
    @Throws(IOException::class)
    fun send(request: AgentRequest): AgentResponse {
        Framing.writeFrame(output, WireCodec.encode(request))
        val responseBytes =
            Framing.readFrame(input)
                ?: throw EOFException(
                    "Agent closed the connection before sending a response to $request"
                )
        return WireCodec.decodeResponse(responseBytes)
    }

    override fun close() {
        channel.close()
    }
}
