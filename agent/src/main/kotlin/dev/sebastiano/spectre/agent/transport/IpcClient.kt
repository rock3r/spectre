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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Client-side IPC endpoint (#200 multiplexed ops).
 *
 * After Hello handshake, every request is an [OpRequest] with a unique [opId]. A background reader
 * thread demultiplexes [OpResponse] frames so a cancel (or a second quick op) can share the
 * connection with a long-running wait without blocking the accept path on the server.
 *
 * Public [send] remains synchronous for [AttachedAutomator]; [cancel] aborts an in-flight op by id.
 *
 * [close] only drops the socket — it does **not** send [AgentRequest.Detach]. Detach is an
 * intentional agent teardown (see [dev.sebastiano.spectre.agent.AttachedAutomator.close]); a plain
 * client close must leave the server free to accept another connection (e.g. reconnect / tests).
 */
internal class IpcClient @Throws(IOException::class) constructor(udsPath: Path) : AutoCloseable {
    private val channel: SocketChannel =
        SocketChannel.open(StandardProtocolFamily.UNIX).also { channel ->
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
    private val writeLock = Any()
    private val nextOpId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableFuture<AgentResponse>>()
    private val closed = AtomicBoolean(false)
    private val readerThread: Thread

    init {
        var handshakeOk = false
        try {
            val hello = AgentRequest.Hello(protocolVersion = ProtocolVersion.CURRENT)
            val ack = exchangeBare(hello)
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
                    val category =
                        when (val decoded = AgentErrorCategory.fromWire(ack.category)) {
                            AgentErrorCategory.InternalError -> AgentErrorCategory.ProtocolMismatch
                            else -> decoded
                        }
                    runCatching { exchangeBare(AgentRequest.Detach) }
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

        readerThread =
            Thread(::readerLoop, "spectre-ipc-client-reader").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Sends [request] and blocks until the correlated response arrives. [deadlineEpochMs] is an
     * absolute epoch-millis deadline propagated to the runtime (#200).
     */
    @Throws(IOException::class)
    fun send(request: AgentRequest, deadlineEpochMs: Long? = null): AgentResponse {
        check(!closed.get()) { "IpcClient is closed" }
        val opId = nextOpId.getAndIncrement()
        val future = CompletableFuture<AgentResponse>()
        pending[opId] = future
        try {
            val frame = OpRequest(opId = opId, deadlineEpochMs = deadlineEpochMs, body = request)
            synchronized(writeLock) { Framing.writeFrame(output, WireCodec.encode(frame)) }
            // Already-elapsed deadlines still need time for the server to return taxonomy
            // `timeout` (Codex P2 / Windows flake: 1ms wait raced the UDS round-trip).
            val waitMs = clientWaitMs(deadlineEpochMs)
            return future.get(waitMs, TimeUnit.MILLISECONDS)
        } catch (ex: java.util.concurrent.TimeoutException) {
            pending.remove(opId)
            runCatching { cancel(opId) }
            throw SpectreAgentException(
                category = AgentErrorCategory.Timeout,
                message = "Timed out waiting for response to ${request.logLabel} (opId=$opId)",
                cause = ex,
            )
        } catch (ex: InterruptedException) {
            // Caller thread interrupted while waiting — cancel the remote op so UI work stops
            // (Codex P2).
            pending.remove(opId)
            runCatching { cancel(opId) }
            Thread.currentThread().interrupt()
            throw SpectreAgentException(
                category = AgentErrorCategory.Cancelled,
                message = "Interrupted while waiting for ${request.logLabel} (opId=$opId)",
                cause = ex,
            )
        } catch (ex: java.util.concurrent.ExecutionException) {
            pending.remove(opId)
            throw unwrapExecutionFailure(opId, ex)
        } finally {
            pending.remove(opId)
        }
    }

    /** Explicit cancel for [opId] (#200). Best-effort; safe if the op already completed. */
    @Throws(IOException::class)
    fun cancel(opId: Long) {
        check(!closed.get()) { "IpcClient is closed" }
        val cancelId = nextOpId.getAndIncrement()
        val future = CompletableFuture<AgentResponse>()
        pending[cancelId] = future
        try {
            val frame = OpRequest(opId = cancelId, body = AgentRequest.Cancel(opId = opId))
            synchronized(writeLock) { Framing.writeFrame(output, WireCodec.encode(frame)) }
            future.get(CANCEL_ACK_WAIT_MS, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            // Best-effort: cancel ack lag is non-fatal.
        } catch (_: java.util.concurrent.ExecutionException) {
            // Best-effort: transport error while waiting for cancel ack.
        } catch (_: java.util.concurrent.CancellationException) {
            // Best-effort: local future cancelled during client close.
        } catch (_: IOException) {
            // Best-effort: write failed (peer already gone).
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            pending.remove(cancelId)
        }
    }

    private fun unwrapExecutionFailure(
        opId: Long,
        ex: java.util.concurrent.ExecutionException,
    ): IOException {
        val cause = ex.cause
        return when (cause) {
            is IOException -> cause
            null -> IOException("Op $opId failed: ${ex.message}", ex)
            else -> IOException("Op $opId failed: ${cause.message}", cause)
        }
    }

    /** Last assigned op id (for tests). */
    internal fun lastOpId(): Long = nextOpId.get() - 1

    private fun readerLoop() {
        try {
            while (!closed.get()) {
                val bytes = Framing.readFrame(input) ?: break
                dispatchOpResponse(bytes)
            }
        } catch (_: Exception) {
            // Channel closed or transport error — complete outstanding futures below.
        } finally {
            failAllPending(EOFException("Agent closed the connection while waiting for a response"))
        }
    }

    private fun dispatchOpResponse(bytes: ByteArray) {
        val opResponse =
            try {
                WireCodec.decodeOpResponse(bytes)
            } catch (_: Exception) {
                // Legacy bare response should not appear after handshake; treat as disconnect.
                failAllPending(EOFException("Unexpected non-envelope response after handshake"))
                return
            }
        pending.remove(opResponse.opId)?.complete(opResponse.body)
    }

    private fun failAllPending(err: Exception) {
        pending.forEach { (_, future) -> future.completeExceptionally(err) }
        pending.clear()
    }

    /** Bare (pre-envelope) exchange used only for Hello / best-effort Detach on failed Hello. */
    @Throws(IOException::class)
    private fun exchangeBare(request: AgentRequest): AgentResponse {
        Framing.writeFrame(output, WireCodec.encode(request))
        val responseBytes =
            Framing.readFrame(input)
                ?: throw EOFException(
                    "Agent closed the connection before sending a response to ${request.logLabel}"
                )
        return WireCodec.decodeResponse(responseBytes)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Drop the socket only — Detach is explicit agent teardown, not implied by close.
        runCatching { channel.close() }
        runCatching { readerThread.join(READER_JOIN_MS) }
        failAllPending(EOFException("IpcClient closed"))
    }

    private fun clientWaitMs(deadlineEpochMs: Long?): Long {
        if (deadlineEpochMs == null) return DEFAULT_WAIT_MS
        val remaining = deadlineEpochMs - System.currentTimeMillis()
        // Always pad so a server-side timeout response can still arrive after the deadline.
        return remaining.coerceAtLeast(0L) + ELAPSED_DEADLINE_GRACE_MS
    }

    private companion object {
        const val DEFAULT_WAIT_MS: Long = 120_000
        /** Floor so a server-side timeout/cancel response can arrive over UDS. */
        const val ELAPSED_DEADLINE_GRACE_MS: Long = 5_000
        const val CANCEL_ACK_WAIT_MS: Long = 5_000
        const val READER_JOIN_MS: Long = 1_000
    }
}
