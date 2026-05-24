package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-side IPC endpoint: binds a Unix Domain Socket at [udsPath], accepts a single client
 * connection at a time, and dispatches each [AgentRequest] frame to [handler], writing the
 * resulting [AgentResponse] back over the same channel.
 *
 * V1 limits itself to one concurrent attached client — Spectre's automation model is intrinsically
 * serial (one set of windows, one focus state, one input device), so layering client multiplexing
 * on top would just paint over reality. Multi-client support, if it ever lands, is a v1.1 problem.
 *
 * The accept loop runs on a single daemon thread; per-request work happens inline on that thread.
 * [close] interrupts the loop, closes the server channel, and unlinks the UDS path.
 *
 * Created and managed by [dev.sebastiano.spectre.agent.runtime.SpectreAgent] inside the target JVM
 * after the classloader bootstrap succeeds.
 */
internal class IpcServer
@Throws(IOException::class)
constructor(
    private val udsPath: Path,
    private val handler: AgentRequestHandler,
    private val onDetach: () -> Unit = {},
) : AutoCloseable {
    private val serverChannel: ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
            // Clean up any orphaned socket file from a previous crash; Linux/macOS refuse to bind
            // if the path already exists, even when stale.
            Files.deleteIfExists(udsPath)
            channel.bind(UnixDomainSocketAddress.of(udsPath))
            // Tighten the socket file permissions to mode 0600 (owner read/write). UDS file
            // creation respects the process umask, which on common defaults (022) leaves the
            // socket group/world-readable — broader than the local-only trust model claims.
            // Explicitly setting the permissions immediately after bind closes that gap.
            try {
                Files.setPosixFilePermissions(udsPath, OWNER_ONLY_PERMS)
            } catch (ex: UnsupportedOperationException) {
                // Non-POSIX filesystem (e.g. Windows in some setups). Close the channel and
                // unlink so we don't expose a permissionless socket.
                runCatching { channel.close() }
                runCatching { Files.deleteIfExists(udsPath) }
                throw IOException(
                    "Filesystem at $udsPath doesn't support POSIX permissions; refusing to " +
                        "expose a UDS without 0600 access control.",
                    ex,
                )
            } catch (ex: FileSystemException) {
                runCatching { channel.close() }
                runCatching { Files.deleteIfExists(udsPath) }
                throw IOException(
                    "Failed to set 0600 permissions on UDS at $udsPath: ${ex.message}",
                    ex,
                )
            }
        }

    private val running = AtomicBoolean(true)

    private val acceptThread =
        Thread(::acceptLoop, "spectre-agent-accept").apply {
            isDaemon = true
            start()
        }

    /** The UDS path this server is listening on. Exposed for diagnostic logging. */
    val listeningAt: Path
        get() = udsPath

    private fun acceptLoop() {
        try {
            while (running.get()) {
                val client = serverChannel.accept() ?: break
                handleConnection(client)
            }
        } catch (_: ClosedChannelException) {
            // Normal shutdown — close() closes the server channel which unblocks accept().
        } catch (ex: IOException) {
            if (running.get()) {
                System.err.println("[spectre-agent] accept loop terminated: ${ex.message}")
            }
        }
    }

    private fun handleConnection(client: SocketChannel) {
        client.use { socket ->
            val input = Channels.newInputStream(socket)
            val output = Channels.newOutputStream(socket)
            var keepReading = true
            while (running.get() && keepReading) {
                keepReading = handleOneRequest(input, output)
            }
        }
    }

    /**
     * Reads one request frame, dispatches it to the handler, writes the response. Returns `false`
     * on clean EOF or after processing an [AgentRequest.Detach] — the caller uses the return value
     * as the single loop-exit signal.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleOneRequest(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ): Boolean {
        val requestBytes = Framing.readFrame(input) ?: return false
        val request =
            try {
                WireCodec.decodeRequest(requestBytes)
            } catch (ex: kotlinx.serialization.SerializationException) {
                Framing.writeFrame(
                    output,
                    WireCodec.encode(AgentResponse.Error("Malformed request: ${ex.message}")),
                )
                return true
            }

        // Generic RuntimeException catch: an automator handler may throw NPE / CCE / ISE when
        // the target's `ComposeAutomator` is in an unexpected state. The IPC layer's job is
        // to turn any such failure into a wire-level `AgentResponse.Error` rather than crash
        // the accept loop and orphan the connection. Detekt's TooGenericExceptionCaught is
        // suppressed here with rationale.
        val response: AgentResponse =
            try {
                handler.handle(request)
            } catch (ex: RuntimeException) {
                AgentResponse.Error("${ex.javaClass.simpleName}: ${ex.message ?: "<no message>"}")
            }
        Framing.writeFrame(output, WireCodec.encode(response))
        if (request is AgentRequest.Detach) {
            running.set(false)
            onDetach()
            return false
        }
        return true
    }

    /**
     * Closes the server channel and unlinks the UDS path. Safe to call any number of times — the
     * first call performs the work, subsequent calls are no-ops.
     *
     * Decoupled from the `running` flag because `running` may already be `false` when an
     * [AgentRequest.Detach] flipped it on the handler thread. We still need [close] to release the
     * channel and unlink the path in that case, which the previous CAS-gated implementation skipped
     * (and which `IpcRoundTripTest` caught).
     */
    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        running.set(false)
        try {
            serverChannel.close()
        } catch (_: IOException) {
            // Best-effort.
        }
        try {
            Files.deleteIfExists(udsPath)
        } catch (_: IOException) {
            // Best-effort — if the file is gone or undeletable, the shutdown hook reaps it.
        }
        acceptThread.interrupt()
    }
}

/**
 * Strategy interface for handling [AgentRequest]s. Implementations live in the target's classloader
 * (where Spectre's `core` types resolve) so they can invoke `ComposeAutomator` methods reflectively
 * without crossing classloader boundaries at type-resolution time.
 */
internal fun interface AgentRequestHandler {
    fun handle(request: AgentRequest): AgentResponse
}

private val OWNER_ONLY_PERMS =
    PosixFilePermissions.fromString("rw-------").also {
        // Sanity assertion: the parsed set should be exactly OWNER_READ + OWNER_WRITE. Cheap
        // self-check so a typo here surfaces at module-init time rather than only when a peer
        // tries to connect.
        require(it == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) {
            "Expected OWNER_ONLY_PERMS to be 0600 (rw-------), got $it"
        }
    }
