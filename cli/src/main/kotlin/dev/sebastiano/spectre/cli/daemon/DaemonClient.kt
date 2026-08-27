package dev.sebastiano.spectre.cli.daemon

import dev.sebastiano.spectre.agent.AttachInputCoordination
import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
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
                if (!ignoresFrameBudget(request)) {
                    frameBudgetMismatchFailure(
                            requested = FrameLimits.requestedMaxFrameBytes,
                            daemonBudget = response.maxFrameBytes,
                        )
                        ?.let { throw IOException(it) }
                }
                if (!ignoresInputCoordination(request)) {
                    inputCoordinationMismatchFailure(
                            requested = AttachInputCoordination.requestedFromProperty(),
                            daemonMode = response.inputCoordination,
                        )
                        ?.let { throw IOException(it) }
                }
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
 * Requests exempt from the frame-budget check.
 *
 * The mismatch error tells the user to run `spectre daemon kill`, which inherits the same
 * `SPECTRE_MAX_FRAME_BYTES` and would hit the same check — leaving the documented recovery path a
 * dead end and the daemon killable only by hand. Shutdown carries no bulky payload, so exempting it
 * costs nothing.
 */
internal fun ignoresFrameBudget(request: DaemonRequest): Boolean = request is DaemonRequest.Shutdown

/**
 * Requests exempt from the coordination-mode check, for the same reason as [ignoresFrameBudget]:
 * the mismatch error tells the user to run `spectre daemon kill`, which inherits the same `-D` and
 * would hit this check, leaving the documented recovery a dead end.
 */
internal fun ignoresInputCoordination(request: DaemonRequest): Boolean =
    request is DaemonRequest.Shutdown

/**
 * Explains why a requested desktop input coordination mode cannot take effect, or `null` when it
 * can.
 *
 * The daemon resolves the mode once, from its own system properties, and every target it injects
 * inherits that. A `-D` on a later invocation therefore reaches the CLI process and nothing else.
 *
 * That would be a footnote for most settings, but this one is the #472 escape hatch, and the
 * journey it exists for *guarantees* a daemon is already running: you only reach for it after an
 * attach has failed. Silently ignoring it there would make the documented recovery appear broken —
 * the exact failure the hatch was added to remove.
 *
 * Checked in both directions. Asking to disable coordination on a coordinated daemon leaves the
 * user stuck; asking to restore it on a daemon left disabled by an earlier recovery session is
 * worse, because that daemon attaches every new target uncoordinated.
 *
 * **Asking for nothing means asking for [AttachInputCoordination.Required]**, and this is the one
 * place the [frameBudgetMismatchFailure] analogy breaks. A budget is a capacity setting, so a
 * client that requested none is genuinely happy with any daemon. Coordination is a safety property:
 * a user who has taken the opt-out back off is asking for the documented default, and accepting a
 * still-disabled daemon would leave every later attach uncoordinated until they happened to restart
 * it.
 *
 * That would also be silent. An earlier revision of this function reasoned that a disabled daemon
 * announces itself on stderr. It does — into a startup log `DaemonProcessLauncher` deletes as soon
 * as the daemon is up, so on this path the announcement reaches nobody.
 *
 * A daemon that reports no mode is refused outright, whatever was asked. It cannot say whether it
 * coordinates, and for a safety property an unanswerable question is not a yes — see the comment on
 * that branch for why its protocol version cannot stand in for the answer.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun inputCoordinationMismatchFailure(
    requested: AttachInputCoordination?,
    daemonMode: String?,
): String? {
    val effective = requested ?: AttachInputCoordination.Required
    if (daemonMode == null) {
        // Fails closed, and deliberately so. It is tempting to reason that a daemon predating this
        // field also predates the opt-out and is therefore coordinated -- but the dates rule that
        // out: DaemonProtocol.CurrentVersion reached 1.12 on 2026-08-19 (9a15371) and coordination
        // itself only landed on 2026-08-24 (b5edbc5). A daemon built in between reports 1.12,
        // satisfies every compatibility floor, answers nothing here, and injects the legacy
        // no-argument RobotDriver, which coordinates nothing at all. Nothing on this wire tells it
        // apart from a daemon that would coordinate properly.
        //
        // "I cannot tell" must not be reported as "yes" for a safety property; doing so is the
        // silent default-defeat #472 exists to remove. One `spectre daemon kill` after upgrading
        // is the whole cost.
        return coordinationMismatchMessage(
            asked = "-D${AttachInputCoordination.PROPERTY}=${effective.wireValue}",
            running =
                "a version that predates coordination reporting, so it cannot say whether it " +
                    "coordinates at all",
            keepRunningMode = null,
        )
    }
    if (daemonMode == effective.wireValue) return null
    val asked =
        if (requested == null) {
            "the default desktop input coordination mode (${effective.wireValue})"
        } else {
            "-D${AttachInputCoordination.PROPERTY}=${requested.wireValue}"
        }
    return coordinationMismatchMessage(
        asked = asked,
        running = daemonMode,
        keepRunningMode = daemonMode,
    )
}

/**
 * [keepRunningMode] is the daemon's mode spelled as the property value that would ask for it, or
 * `null` when there is no such spelling to offer.
 *
 * It has to carry the value. A bare `-D<property>` sets the property to the empty string, which
 * [AttachInputCoordination.requestedFromProperty] reads as blank, which is no request at all, which
 * resolves back to `Required` and produces this same failure — advice that loops straight back to
 * the error it is attached to.
 */
private fun coordinationMismatchMessage(
    asked: String,
    running: String,
    keepRunningMode: String?,
): String {
    val keep =
        keepRunningMode
            ?.let {
                ", or pass -D${AttachInputCoordination.PROPERTY}=$it on this command if you " +
                    "meant to keep the mode the daemon is already running"
            }
            .orEmpty()
    return "Cannot honour $asked: the running Spectre daemon started with $running, and a daemon " +
        "keeps the desktop input coordination mode it booted with. It resolves that mode once, " +
        "and every target it injects inherits it. Run `spectre daemon kill` and retry so the " +
        "mode applies to the daemon and to the JVMs it injects$keep. " +
        "See https://github.com/rock3r/spectre/issues/472"
}

/**
 * Explains why a requested frame budget cannot take effect, or `null` when it can.
 *
 * The daemon is long-lived and shared, so it keeps the budget it booted with: `--max-frame-bytes`
 * on a later invocation would otherwise apply to the CLI alone while the two hops that actually
 * carry a screenshot — target to daemon, daemon to CLI — stayed on the old value.
 *
 * Only an explicit request is worth failing over, which is why [requested] is nullable rather than
 * compared against [DEFAULT_MAX_FRAME_BYTES]: a client that asked for nothing is happy with any
 * daemon, since readers accept frames up to the fixed ceiling whatever their own budget, while a
 * client that explicitly asked for the default-sized budget is still asking. A daemon too old to
 * report its budget cannot have been started with the requested one, so it is refused the same way
 * rather than assumed.
 */
@OptIn(ExperimentalSpectreAgentApi::class)
internal fun frameBudgetMismatchFailure(requested: Int?, daemonBudget: Int?): String? {
    if (requested == null) return null
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
