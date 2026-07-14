package dev.sebastiano.spectre.cli.daemon

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** One-request client for the local Spectre daemon protocol. */
public class DaemonClient(public val socketPath: Path) : AutoCloseable {
    /** Starts the daemon when its endpoint is absent, then sends [request]. */
    @Throws(IOException::class)
    public fun requestOrStart(request: DaemonRequest, start: () -> Unit): DaemonResponse =
        DaemonStartupCoordinator(
                connect = { requestWithAbsentEndpointCheck(request) },
                start = start,
            )
            .connectOrStart()

    /** Sends one compatible request and returns the daemon's response. */
    @Throws(IOException::class)
    public fun request(request: DaemonRequest): DaemonResponse =
        SocketChannel.open(StandardProtocolFamily.UNIX).use { channel ->
            channel.connect(UnixDomainSocketAddress.of(socketPath))
            val input = Channels.newInputStream(channel)
            val output = Channels.newOutputStream(channel)
            try {
                requireCompatibleDaemon(output, input, request)
            } catch (exception: EOFException) {
                throw DaemonConnectionClosedException(exception)
            } catch (exception: SocketException) {
                throw DaemonConnectionClosedException(exception)
            }
            DaemonWireCodec.writeRequest(output, request)
            DaemonWireCodec.readResponse(input)
                ?: throw IOException("Daemon closed the connection before responding")
        }

    private fun requireCompatibleDaemon(
        output: java.io.OutputStream,
        input: java.io.InputStream,
        request: DaemonRequest,
    ) {
        val requiredVersion = DaemonProtocol.minimumDaemonVersion(request)
        DaemonWireCodec.writeRequest(output, DaemonRequest.Hello(requiredVersion))
        when (val response = DaemonWireCodec.readResponse(input)) {
            is DaemonResponse.Hello -> {
                if (
                    DaemonProtocol.checkCompatibility(
                        client = requiredVersion,
                        daemon = response.daemonVersion,
                    ) != VersionCompatibility.Compatible
                ) {
                    throw IOException(
                        "Incompatible daemon protocol version ${response.daemonVersion}"
                    )
                }
            }
            is DaemonResponse.Error ->
                throw IOException("Daemon handshake failed: ${response.message}")
            null -> throw DaemonConnectionClosedException()
            else -> throw IOException("Daemon returned an unexpected handshake response")
        }
    }

    override fun close(): Unit = Unit

    private fun requestWithAbsentEndpointCheck(request: DaemonRequest): DaemonResponse =
        try {
            request(request)
        } catch (exception: SocketException) {
            if (Files.exists(socketPath)) throw exception
            throw NoSuchFileException(socketPath.toString()).also { it.initCause(exception) }
        }
}

internal class DaemonConnectionClosedException(cause: Throwable? = null) :
    IOException("Daemon closed the connection during handshake", cause)
