package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.SpectreAgentException
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
 * response. The current wire protocol is strictly request/response (no streaming), so this is safe
 * to use without explicit pipelining.
 *
 * Not thread-safe — callers that need concurrent automator access should synchronise externally.
 * The `AttachedAutomator` wrapper exposes only serial operations.
 */
internal class IpcClient @Throws(IOException::class) constructor(udsPath: Path) : AutoCloseable {
    private val channel: SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
            // Outer `success` sentinel + `finally` so the channel is unconditionally closed
            // if `connect()` throws (e.g. agent hasn't bound yet, connection refused, path
            // doesn't exist). Without this, the opened `SocketChannel` would only get
            // reclaimed on the next GC cycle via `sun.nio.ch.SocketChannelImpl`'s registered
            // `Cleaner` — relying on GC for resource lifecycle is anti-pattern. Same fix
            // shape as `IpcServer`'s constructor (Bugbot LOW on b98e93c) applied to the
            // symmetric client side. Bugbot caught this side too (LOW). No unit-level
            // regression test — the GC-reclaim path makes an FD-count assertion flaky
            // (see the explanatory NOTE in `IpcRoundTripTest`).
            var success = false
            try {
                channel.connect(UnixDomainSocketAddress.of(udsPath))
                success = true
            } finally {
                if (!success) runCatching { channel.close() }
            }
        }
    private val input: InputStream = Channels.newInputStream(channel)
    private val output: OutputStream = Channels.newOutputStream(channel)

    init {
        // #199: exact-match protocol handshake is the first exchange after connect.
        // Always close the channel if handshake does not complete cleanly (no FD leak).
        var handshakeOk = false
        try {
            val hello = AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT)
            val ack = sendWithoutHandshake(hello)
            when (ack) {
                is AgentResponse.HelloAck -> {
                    if (ack.protocolVersion != ProtocolVersion.CURRENT) {
                        throw SpectreAgentException(
                            category = AgentErrorCategory.ProtocolMismatch,
                            message =
                                "Agent protocol mismatch: runtime advertised " +
                                    "${ack.protocolVersion}, client expects " +
                                    "${ProtocolVersion.CURRENT}",
                        )
                    }
                    handshakeOk = true
                }
                is AgentResponse.Error -> {
                    // Pre-#199 runtimes return message-only Error (defaults to internalError)
                    // when they cannot decode Hello — treat as protocol mismatch, not internal.
                    val category =
                        when (val decoded = AgentErrorCategory.fromWire(ack.category)) {
                            AgentErrorCategory.InternalError -> AgentErrorCategory.ProtocolMismatch
                            else -> decoded
                        }
                    throw SpectreAgentException(
                        category = category,
                        message =
                            "Agent rejected protocol handshake " +
                                "(${category.wireName}): ${ack.message}",
                    )
                }
                else -> {
                    throw SpectreAgentException(
                        category = AgentErrorCategory.ProtocolMismatch,
                        message =
                            "Agent protocol handshake expected HelloAck, got " +
                                "${ack::class.simpleName}",
                    )
                }
            }
        } finally {
            if (!handshakeOk) runCatching { channel.close() }
        }
    }

    /**
     * Sends [request], blocks until the matching response arrives, returns it. Throws [IOException]
     * if the channel closes mid-exchange or the wire protocol is violated.
     */
    @Throws(IOException::class)
    fun send(request: AgentRequest): AgentResponse = sendWithoutHandshake(request)

    @Throws(IOException::class)
    private fun sendWithoutHandshake(request: AgentRequest): AgentResponse {
        Framing.writeFrame(output, WireCodec.encode(request))
        val responseBytes =
            Framing.readFrame(input)
                ?: throw EOFException(
                    "Agent closed the connection before sending a response to ${request.logLabel}"
                )
        return WireCodec.decodeResponse(responseBytes)
    }

    override fun close() {
        channel.close()
    }
}
