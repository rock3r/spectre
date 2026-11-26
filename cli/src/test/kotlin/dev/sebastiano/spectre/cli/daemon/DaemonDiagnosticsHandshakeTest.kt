package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.FrameLimits
import dev.sebastiano.spectre.agent.transport.MIN_MAX_FRAME_BYTES
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonDiagnosticsHandshakeTest {
    @Test
    fun `current handshake receives diagnostics while old handshake fails closed`() {
        val socketPath = temporaryDaemonClientSocketPath()
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                nodesResult = {
                    RequestDiagnostics.report("degraded tree")
                    emptyList()
                }
            )
        }
        val server = DaemonServer(socketPath, registry)
        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(73))
            )
            val current =
                assertIs<DaemonResponse.Diagnosed>(
                    DaemonClient(socketPath, retainDiagnostics = true)
                        .request(DaemonRequest.AllNodes("pid-73"))
                )
            assertEquals(listOf("degraded tree"), current.warnings)

            SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
                channel.connect(UnixDomainSocketAddress.of(socketPath))
                val input = Channels.newInputStream(channel)
                val output = Channels.newOutputStream(channel)
                DaemonWireCodec.writeRequest(
                    output,
                    DaemonRequest.Hello(DaemonProtocolVersion(major = 1, minor = 14)),
                )
                assertIs<DaemonResponse.Hello>(DaemonWireCodec.readResponse(input))
                DaemonWireCodec.writeRequest(output, DaemonRequest.AllNodes("pid-73"))
                val error = assertIs<DaemonResponse.Error>(DaemonWireCodec.readResponse(input))
                assertEquals(DaemonErrorCode.ProtocolError, error.code)
                assertTrue(error.message.contains("too old"))
            }
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(socketPath)
        }
    }

    @Test
    fun `near-capacity ordinary response remains deliverable when diagnostics exceed small frame`() {
        FrameLimits.configure(MIN_MAX_FRAME_BYTES)
        val payload = ByteArray(MIN_MAX_FRAME_BYTES - 512) { (it % 251).toByte() }
        val socketPath = temporaryDaemonClientSocketPath()
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                screenshotResult = { _, _, _ ->
                    repeat(20) { index ->
                        RequestDiagnostics.report("diagnostic-$index-é" + "x".repeat(4_000))
                    }
                    payload
                }
            )
        }
        val server = DaemonServer(socketPath, registry)
        try {
            val attached =
                assertIs<DaemonResponse.Attached>(
                    DaemonClient(socketPath).request(DaemonRequest.Attach(74))
                )
            val response =
                DaemonClient(socketPath, retainDiagnostics = true)
                    .request(DaemonRequest.Screenshot(attached.sessionId))
            val diagnosed = assertIs<DaemonResponse.Diagnosed>(response)
            assertContentEquals(
                payload,
                assertIs<DaemonResponse.Screenshot>(diagnosed.response).pngBytes,
            )
            assertTrue(diagnosed.warnings.last().contains("discarded 20 warning(s)"))
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(socketPath)
            FrameLimits.resetToEnvironment()
        }
    }

    @Test
    fun `bare near-capacity response is used when diagnostic marker cannot fit`() {
        FrameLimits.configure(MIN_MAX_FRAME_BYTES)
        val sessionId = "pid-75"
        var payload = ByteArray(MIN_MAX_FRAME_BYTES)
        while (
            DaemonWireCodec.encodeResponse(DaemonResponse.Screenshot(sessionId, payload)).size >
                MIN_MAX_FRAME_BYTES
        ) {
            payload = payload.copyOf(payload.size - 1)
        }
        val expectedPayload = payload
        val socketPath = temporaryDaemonClientSocketPath()
        val registry = DaemonSessionRegistry {
            TestDaemonSessionAutomator(
                screenshotResult = { _, _, _ ->
                    RequestDiagnostics.report("diagnostic that cannot fit")
                    expectedPayload
                }
            )
        }
        val server = DaemonServer(socketPath, registry)
        try {
            assertIs<DaemonResponse.Attached>(
                DaemonClient(socketPath).request(DaemonRequest.Attach(75))
            )
            val response =
                DaemonClient(socketPath, retainDiagnostics = true)
                    .request(DaemonRequest.Screenshot(sessionId))

            assertContentEquals(
                expectedPayload,
                assertIs<DaemonResponse.Screenshot>(response).pngBytes,
            )
        } finally {
            server.close()
            assertTrue(server.awaitTermination())
            deleteTemporaryDaemonClientSocketPath(socketPath)
            FrameLimits.resetToEnvironment()
        }
    }
}
