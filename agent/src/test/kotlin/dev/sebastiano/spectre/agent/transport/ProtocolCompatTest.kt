@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.deleteIfExists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Wire-protocol compatibility (#199): version handshake, unknown-op → unsupportedOperation, and
 * taxonomy-bearing Error responses — without a child Compose fixture.
 */
@EnabledOnOs(OS.LINUX, OS.MAC, OS.WINDOWS)
class ProtocolCompatTest {
    private val udsPath: Path =
        udsBase().resolve("sp-pc-${UUID.randomUUID().toString().take(8)}.sock")

    @AfterTest
    fun cleanUp() {
        runCatching { udsPath.deleteIfExists() }
    }

    @Test
    fun `IpcClient handshake exchanges Hello and HelloAck at CURRENT version`() {
        IpcServer(udsPath, stubHandler()).use {
            awaitSocket(udsPath)
            // Constructing IpcClient performs the handshake; send Ping proves the session works.
            IpcClient(udsPath).use { client ->
                assertEquals(AgentResponse.Pong, client.send(AgentRequest.Ping))
            }
        }
    }

    @Test
    fun `Hello with mismatched protocol version returns protocolMismatch`() {
        IpcServer(udsPath, stubHandler()).use {
            awaitSocket(udsPath)
            // Open a raw socket and send Hello without using IpcClient's init handshake so we
            // control the advertised version.
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(udsPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                Framing.writeFrame(
                    output,
                    WireCodec.encode(
                        AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT + 99)
                    ),
                )
                val bytes = Framing.readFrame(input) ?: error("no response")
                val resp = WireCodec.decodeResponse(bytes)
                val err = assertIs<AgentResponse.Error>(resp)
                assertEquals(AgentErrorCategory.ProtocolMismatch.wireName, err.category)
                assertTrue(err.message.contains("mismatch", ignoreCase = true))
            }
        }
    }

    @Test
    fun `unknown request discriminator yields unsupportedOperation not hang`() {
        IpcServer(udsPath, stubHandler()).use {
            awaitSocket(udsPath)
            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(udsPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                // Complete handshake first so the unknown op is the second frame.
                Framing.writeFrame(
                    output,
                    WireCodec.encode(AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT)),
                )
                val ackBytes = Framing.readFrame(input) ?: error("no helloAck")
                assertIs<AgentResponse.HelloAck>(WireCodec.decodeResponse(ackBytes))

                Framing.writeFrame(output, futureOpRequestBytes())
                val respBytes = Framing.readFrame(input) ?: error("no error response")
                val resp = WireCodec.decodeResponse(respBytes)
                val err = assertIs<AgentResponse.Error>(resp)
                assertEquals(AgentErrorCategory.UnsupportedOperation.wireName, err.category)
            }
        }
    }

    @Test
    fun `Error with category round-trips and defaults for legacy message-only payloads`() {
        val withCategory =
            AgentResponse.Error(
                message = "no such node",
                category = AgentErrorCategory.NodeNotFound.wireName,
            )
        assertEquals(withCategory, WireCodec.decodeResponse(WireCodec.encode(withCategory)))

        // Legacy runtimes only sent `message`; ignoreUnknownKeys + default category keep decode
        // working when we re-encode a message-only shape via the data class default.
        val legacy = AgentResponse.Error(message = "boom")
        assertEquals(AgentErrorCategory.InternalError.wireName, legacy.category)
        assertEquals(legacy, WireCodec.decodeResponse(WireCodec.encode(legacy)))
    }

    @Test
    fun `isUnknownDiscriminator detects polymorphic serializer failures`() {
        val bytes = futureOpRequestBytes()
        val ex =
            try {
                WireCodec.decodeRequest(bytes)
                error("expected SerializationException")
            } catch (ex: SerializationException) {
                ex
            }
        assertTrue(
            WireCodec.isUnknownDiscriminator(ex),
            "expected unknown-discriminator classification for: ${ex.message}",
        )
    }

    private fun stubHandler(): AgentRequestHandler = AgentRequestHandler { request ->
        when (request) {
            AgentRequest.Ping -> AgentResponse.Pong
            is AgentRequest.Hello -> AgentResponse.HelloAck()
            else ->
                AgentResponse.Error(
                    message = "stub unhandled ${request.logLabel}",
                    category = AgentErrorCategory.UnsupportedOperation.wireName,
                )
        }
    }

    private fun awaitSocket(path: Path, timeoutMs: Long = 5_000) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (System.nanoTime() < deadline) {
            if (java.nio.file.Files.exists(path)) return
            Thread.sleep(10)
        }
        error("UDS path $path did not appear within ${timeoutMs}ms")
    }

    private fun udsBase(): Path =
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true))
            Path.of(System.getProperty("java.io.tmpdir"))
        else Path.of("/tmp")

    /**
     * CBOR bytes for a sealed [AgentRequest] whose polymorphic discriminator is a synthetic future
     * op name — models a newer attacher talking to the current runtime.
     */
    private fun futureOpRequestBytes(): ByteArray {
        // Encode a real Ping, then replace its "ping" discriminator with "futureOp_v99".
        // kotlinx CBOR sealed encoding embeds the @SerialName as UTF-8 text in the payload.
        val ping = WireCodec.encode(AgentRequest.Ping)
        val asLatin1 = ping.toString(Charsets.ISO_8859_1)
        require(asLatin1.contains("ping")) { "expected ping discriminator in CBOR payload" }
        // Same length as "ping" so CBOR string length prefixes stay valid.
        return asLatin1.replace("ping", "zzzz").toByteArray(Charsets.ISO_8859_1)
    }
}
