package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalSpectreAgentApi::class)
class DaemonDiagnosticsHandshakeTest {
    @Test
    fun `current handshake receives diagnostics while old handshake fails closed`() {
        val socketDirectory = Files.createTempDirectory("spectre-diagnostics-handshake")
        val socketPath = socketDirectory.resolve("daemon.sock")
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
            Files.deleteIfExists(socketDirectory)
        }
    }
}
