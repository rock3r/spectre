package dev.sebastiano.spectre.cli.daemon

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path

/** One-request client for the local Spectre daemon protocol. */
public class DaemonClient(public val socketPath: Path) : AutoCloseable {
    /** Sends one compatible request and returns the daemon's response. */
    @Throws(IOException::class)
    public fun request(request: DaemonRequest): DaemonResponse =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val input = Channels.newInputStream(channel)
            val output = Channels.newOutputStream(channel)
            requireCompatibleDaemon(output, input)
            DaemonWireCodec.writeRequest(output, request)
            DaemonWireCodec.readResponse(input)
                ?: throw IOException("Daemon closed the connection before responding")
        }

    private fun requireCompatibleDaemon(output: java.io.OutputStream, input: java.io.InputStream) {
        DaemonWireCodec.writeRequest(output, DaemonRequest.Hello(DaemonProtocol.CurrentVersion))
        when (val response = DaemonWireCodec.readResponse(input)) {
            is DaemonResponse.Hello ->
                check(
                    DaemonProtocol.checkCompatibility(
                        client = DaemonProtocol.CurrentVersion,
                        daemon = response.daemonVersion,
                    ) == VersionCompatibility.Compatible
                ) {
                    "Incompatible daemon protocol version ${response.daemonVersion}"
                }
            is DaemonResponse.Error ->
                throw IOException("Daemon handshake failed: ${response.message}")
            null ->
                throw IOException("Daemon closed the connection before completing the handshake")
            else -> throw IOException("Daemon returned an unexpected handshake response")
        }
    }

    override fun close(): Unit = Unit
}
