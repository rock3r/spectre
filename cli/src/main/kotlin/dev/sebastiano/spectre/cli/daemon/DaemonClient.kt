package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import dev.sebastiano.spectre.agent.transport.DEFAULT_MAX_FRAME_BYTES
import dev.sebastiano.spectre.agent.transport.FrameLimits
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
@OptIn(ExperimentalSpectreAgentApi::class)
public class DaemonClient(public val socketPath: Path) : AutoCloseable {
    /** Starts the daemon when its endpoint is absent, then sends [request]. */
    @Throws(IOException::class)
    public fun requestOrStart(request: DaemonRequest, start: () -> Unit): DaemonResponse =
        requestOrStart(request = request, start = start, onAbsent = { null })

    /** Starts the daemon when its endpoint is absent unless [onAbsent] supplies a response. */
    @Throws(IOException::class)
    public fun requestOrStart(
        request: DaemonRequest,
        start: () -> Unit,
        onAbsent: () -> DaemonResponse?,
    ): DaemonResponse =
        DaemonStartupCoordinator(
                connect = { requestWithAbsentEndpointCheck(request) },
                start = start,
                onAbsent = onAbsent,
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

    /** Sends [request], reporting a missing daemon endpoint distinctly from other I/O failures. */
    @Throws(IOException::class)
    public fun requestIfPresent(request: DaemonRequest): DaemonResponse =
        requestWithAbsentEndpointCheck(request)

    private fun requireCompatibleDaemon(
        output: java.io.OutputStream,
        input: java.io.InputStream,
        request: DaemonRequest,
    ) {
        val requiredVersion = DaemonProtocol.minimumDaemonVersion(request)
        DaemonWireCodec.writeRequest(output, DaemonRequest.Hello(requiredVersion))
        when (val response = DaemonWireCodec.readResponse(input)) {
            is DaemonResponse.Hello -> {
                val compatibility =
                    DaemonProtocol.checkCompatibility(
                        client = requiredVersion,
                        daemon = response.daemonVersion,
                    )
                if (compatibility != VersionCompatibility.Compatible) {
                    throw IOException(
                        daemonCompatibilityFailure(requiredVersion, response.daemonVersion)
                    )
                }
                frameBudgetMismatchFailure(
                        requested = FrameLimits.maxFrameBytes,
                        daemonBudget = response.maxFrameBytes,
                    )
                    ?.let { throw IOException(it) }
            }
            is DaemonResponse.Error ->
                throw IOException(daemonHandshakeFailure(requiredVersion, response))
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

/**
 * Explains why a requested frame budget cannot take effect, or `null` when it can.
 *
 * The daemon is long-lived and shared, so it keeps the budget it booted with: `--max-frame-bytes`
 * on a later invocation would otherwise apply to the CLI alone while the two hops that actually
 * carry a screenshot — target to daemon, daemon to CLI — stayed on the old value.
 *
 * Only an explicit request is worth failing over. A client left on [DEFAULT_MAX_FRAME_BYTES] asked
 * for nothing, and readers accept frames up to the fixed ceiling whatever their own budget, so a
 * daemon running something larger is harmless. A daemon too old to report its budget cannot have
 * been started with the requested one, so it is refused the same way rather than assumed.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun frameBudgetMismatchFailure(requested: Int, daemonBudget: Int?): String? {
    if (requested == DEFAULT_MAX_FRAME_BYTES) return null
    if (daemonBudget == requested) return null
    val running =
        if (daemonBudget == null) "a version that predates the setting"
        else "${renderFrameBudget(daemonBudget)} (${daemonBudget} bytes)"
    return "Cannot honour --max-frame-bytes=${renderFrameBudget(requested)} " +
        "(${requested} bytes): the running Spectre daemon started with $running, and a daemon " +
        "keeps the budget it booted with. Run `spectre daemon kill` and retry so the new budget " +
        "applies to the daemon and to the JVMs it injects."
}

/** Renders [bytes] in the largest binary unit that divides it, matching what the flag accepts. */
internal fun renderFrameBudget(bytes: Int): String {
    val units = listOf("GiB" to (1 shl 30), "MiB" to (1 shl 20), "KiB" to (1 shl 10))
    val unit = units.firstOrNull { (_, size) -> bytes >= size && bytes % size == 0 }
    return if (unit == null) "$bytes bytes" else "${bytes / unit.second}${unit.first}"
}

internal fun daemonCompatibilityFailure(
    required: DaemonProtocolVersion,
    daemon: DaemonProtocolVersion,
): String =
    if (daemon.major == required.major && daemon.minor < required.minor) {
        "Spectre daemon protocol ${daemon.major}.${daemon.minor} is too old for this command. " +
            "Run `spectre daemon kill` and retry."
    } else {
        "Incompatible daemon protocol version ${daemon.major}.${daemon.minor}; " +
            "this command requires ${required.major}.${required.minor}."
    }

internal fun daemonHandshakeFailure(
    required: DaemonProtocolVersion,
    response: DaemonResponse.Error,
): String =
    if (
        response.code == DaemonErrorCode.ProtocolError &&
            response.message == "incompatible daemon protocol version" &&
            required.major == DaemonProtocol.CurrentVersion.major &&
            required.minor > 0
    ) {
        "Spectre daemon does not support this command. Run `spectre daemon kill` and retry."
    } else {
        "Daemon handshake failed: ${response.message}"
    }
