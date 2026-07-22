package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ClosedChannelException
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Server-side IPC endpoint: binds a Unix Domain Socket at [udsPath], accepts a single client
 * connection at a time, and dispatches each [AgentRequest] frame to [handler], writing the
 * resulting [AgentResponse] back over the same channel.
 *
 * The current transport limits itself to one concurrent attached client — Spectre's automation
 * model is intrinsically serial (one set of windows, one focus state, one input device), so
 * layering client multiplexing on top would just paint over reality. Multi-client support, if it
 * ever lands, is a follow-up.
 *
 * The accept loop runs on a single daemon thread. After Hello, request bodies run on a worker pool
 * (#200) so long ops do not block cancel/detach dispatch. [close] interrupts the loop, closes the
 * server channel, and unlinks the UDS path.
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
    private var createdParentDir: Path? = null

    private val protection: SocketFileProtection = SocketFileProtection.forPath(udsPath)

    private val serverChannel: ServerSocketChannel =
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
            // Outer `success` sentinel + `finally` so the channel is unconditionally closed on
            // ANY constructor-time failure (bind throwing on a too-long path, `deleteIfExists`
            // failing, the owner-only protection throwing, ...). Without this, an opened
            // `ServerSocketChannel` whose bind fails would leak a native file descriptor for
            // the rest of the JVM's lifetime — `ServerSocketChannel` has no finalizer or
            // cleaner. Bugbot caught it (LOW); the regression test
            // `IpcServer constructor releases the ServerSocketChannel when bind fails` pins
            // the contract by observing `openFileDescriptorCount`.
            var success = false
            try {
                createdParentDir = protection.createMissingParentDirectory(udsPath)
                // Clean up any orphaned socket file from a previous crash; the OS refuses to
                // bind if the path already exists, even when stale.
                Files.deleteIfExists(udsPath)
                channel.bind(UnixDomainSocketAddress.of(udsPath))
                // Tighten the freshly-bound socket to owner-only access (POSIX mode 0600 /
                // Windows owner-only ACL). UDS file creation otherwise respects the ambient umask
                // or inherited ACL, which is broader than the local-only trust model claims.
                protection.protectSocketFile(udsPath)
                success = true
            } finally {
                if (!success) {
                    runCatching { channel.close() }
                    runCatching { Files.deleteIfExists(udsPath) }
                    runCatching { createdParentDir?.let(Files::deleteIfExists) }
                }
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
        while (running.get()) {
            val client =
                try {
                    serverChannel.accept() ?: break
                } catch (_: ClosedChannelException) {
                    // Normal shutdown — close() closes the server channel, which unblocks
                    // accept(). Exit the loop cleanly.
                    return
                } catch (ex: IOException) {
                    // accept() itself failed (e.g. the server channel is broken at the OS
                    // level). There's nothing we can recover here — the channel is unusable.
                    if (running.get()) {
                        System.err.println("[spectre-agent] accept failed: ${ex.message}")
                    }
                    return
                }
            // Per-connection failures (broken pipe from a crashed client mid-`writeFrame`,
            // half-closed sockets, malformed framing, …) MUST NOT kill the accept loop.
            // Before this catch was inside the loop, an `IOException` from
            // `handleConnection` propagated to the outer catch, terminated the accept
            // thread, and — combined with `SpectreAgent.bootstrap`'s "already bootstrapped"
            // idempotency guard — left the agent permanently unreachable for the rest of
            // the target JVM's lifetime. Bugbot caught the original (MEDIUM); the regression
            // test in `IpcRoundTripTest` ("server survives a client that closes mid-request
            // …") pins the contract.
            //
            // Catch widened from `IOException` to `Exception` for the same reason: a
            // misbehaving client can send a malformed length prefix and `Framing.readFrame`'s
            // `check(length in 0..MAX_FRAME_BYTES)` throws `IllegalStateException` (not an
            // `IOException`). The pinned regression test "server survives malformed frame
            // length prefix" covers that path. Errors (OOM, StackOverflow) are intentionally
            // NOT caught. Detekt's TooGenericExceptionCaught is suppressed with rationale.
            @Suppress("TooGenericExceptionCaught")
            try {
                handleConnection(client)
            } catch (ex: Exception) {
                if (running.get()) {
                    System.err.println(
                        "[spectre-agent] connection terminated " +
                            "(${ex.javaClass.simpleName}): ${ex.message ?: "<no message>"}"
                    )
                }
            }
        }
    }

    private fun handleConnection(client: SocketChannel) {
        client.use { socket ->
            val input = Channels.newInputStream(socket)
            val output = Channels.newOutputStream(socket)
            // Phase 1: bare Hello (#199).
            if (!performHandshake(input, output)) return
            // Phase 2: multiplexed OpRequest frames on a worker pool (#200).
            MultiplexedIpcSession(handler, running, onDetach).run(input, output)
        }
    }

    /** Bare Hello handshake. Returns false if the connection should end. */
    private fun performHandshake(input: InputStream, output: OutputStream): Boolean {
        var handshakeComplete = false
        while (running.get() && !handshakeComplete) {
            val requestBytes =
                try {
                    Framing.readFrame(input) ?: return false
                } catch (ex: IllegalStateException) {
                    running.set(false)
                    onDetach()
                    throw ex
                }
            val request =
                try {
                    WireCodec.decodeRequest(requestBytes)
                } catch (ex: kotlinx.serialization.SerializationException) {
                    respondDecodeFailure(output, ex, handshakeComplete = false)
                    return false
                }
            when (request) {
                is AgentRequest.Hello -> {
                    val ok = respondHello(output, request) { handshakeComplete = it }
                    if (!ok) return false
                }
                else -> {
                    rejectPreHandshake(output, request)
                    return false
                }
            }
        }
        return handshakeComplete
    }

    /** Decode failure response; pre-handshake failures tear down agent state (#199). */
    private fun respondDecodeFailure(
        output: OutputStream,
        ex: kotlinx.serialization.SerializationException,
        handshakeComplete: Boolean,
    ): Boolean {
        val category =
            if (WireCodec.isUnknownDiscriminator(ex)) {
                AgentErrorCategory.UnsupportedOperation
            } else {
                AgentErrorCategory.ProtocolMismatch
            }
        val tearDown = !handshakeComplete
        try {
            Framing.writeFrame(
                output,
                WireCodec.encode(
                    AgentResponse.Error(
                        message = "Malformed or unsupported request: ${ex.message}",
                        category = category.wireName,
                    )
                ),
            )
        } finally {
            if (tearDown) {
                running.set(false)
                onDetach()
            }
        }
        return !tearDown
    }

    /** Hello handshake; mismatch tears down agent state so re-attach can re-bootstrap (#199). */
    private fun respondHello(
        output: OutputStream,
        request: AgentRequest.Hello,
        setHandshakeComplete: (Boolean) -> Unit,
    ): Boolean {
        if (request.protocolVersion == ProtocolVersion.CURRENT) {
            setHandshakeComplete(true)
            Framing.writeFrame(
                output,
                WireCodec.encode(AgentResponse.HelloAck(protocolVersion = ProtocolVersion.CURRENT)),
            )
            return true
        }
        setHandshakeComplete(false)
        try {
            Framing.writeFrame(
                output,
                WireCodec.encode(
                    AgentResponse.Error(
                        message =
                            "Protocol version mismatch: client=${request.protocolVersion}, " +
                                "runtime=${ProtocolVersion.CURRENT} (exact-match required " +
                                "while agent API is experimental)",
                        category = AgentErrorCategory.ProtocolMismatch.wireName,
                    )
                ),
            )
        } finally {
            running.set(false)
            onDetach()
        }
        return false
    }

    /** Non-Hello first frame (pre-#199 attacher); tear down and close the connection (#199). */
    private fun rejectPreHandshake(output: OutputStream, request: AgentRequest): Boolean {
        try {
            Framing.writeFrame(
                output,
                WireCodec.encode(
                    AgentResponse.Error(
                        message =
                            "Protocol handshake required: send Hello before ${request.logLabel}",
                        category = AgentErrorCategory.ProtocolMismatch.wireName,
                    )
                ),
            )
        } finally {
            running.set(false)
            onDetach()
        }
        return false
    }

    /**
     * Closes the server channel and unlinks the UDS path. Safe to call any number of times — the
     * first call performs the work, subsequent calls are no-ops.
     *
     * Decoupled from the `running` flag because `running` may already be `false` when an
     * [AgentRequest.Detach] flipped it on the handler thread. We still need [close] to release the
     * channel and unlink the path in that case, which the previous CAS-gated implementation skipped
     * (and which `IpcRoundTripTest` caught).
     *
     * Uses [AtomicBoolean.compareAndSet] rather than a `@Volatile` boolean with a check-then-set
     * pattern: the shutdown-hook thread (Path B) and the `onClientDetach` call from the accept
     * thread (Path A) can both reach [close] concurrently. The inner ops are already idempotent,
     * but the atomic CAS matches the documented "subsequent calls are no-ops" contract precisely —
     * only the first caller through CAS executes the body, others fall through immediately.
     */
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
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
        try {
            createdParentDir?.let(Files::deleteIfExists)
        } catch (_: IOException) {
            // Best-effort — if the parent is gone or non-empty, leaving it is safer.
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
