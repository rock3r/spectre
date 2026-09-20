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
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
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
internal class IpcClient
@Throws(IOException::class)
constructor(
    udsPath: Path,
    /** Per-frame read/write deadline; `<= 0` disables (tests only). */
    private val frameIoTimeoutMs: Long = FrameIoDeadline.DEFAULT_TIMEOUT_MS,
) : AutoCloseable {
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
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "spectre-ipc-client-writer").apply { isDaemon = true }
    }
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
            if (!handshakeOk) {
                runCatching { channel.close() }
                writer.shutdown()
            }
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
            writeFrameInterruptSafe(WireCodec.encode(frame))
            // No client-side deadline: wait indefinitely for the server (matches pre-#200
            // blocking read). With a deadline: pad so server taxonomy timeout can still arrive.
            return if (deadlineEpochMs == null) {
                future.get()
            } else {
                future.get(clientWaitMs(deadlineEpochMs), TimeUnit.MILLISECONDS)
            }
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
            throw cancelDueToInterrupt(opId, request, ex)
        } catch (ex: java.nio.channels.ClosedByInterruptException) {
            // macOS NIO: SocketChannel.write on an interrupted thread throws this IOException
            // subclass instead of InterruptedException. Same caller intent as above.
            throw cancelDueToInterrupt(opId, request, ex)
        } catch (ex: java.util.concurrent.CancellationException) {
            // CompletableFuture can surface local cancellation as CancellationException rather
            // than InterruptedException on some paths; keep the same cancelled taxonomy.
            throw cancelDueToInterrupt(opId, request, ex)
        } catch (ex: java.util.concurrent.ExecutionException) {
            pending.remove(opId)
            // Race: peer EOF while this thread was interrupted should still report cancelled
            // (interrupt is the caller's intent) rather than a bare IOException.
            if (Thread.currentThread().isInterrupted) {
                runCatching { cancel(opId) }
                Thread.currentThread().interrupt()
                throw SpectreAgentException(
                    category = AgentErrorCategory.Cancelled,
                    message =
                        "Interrupted while waiting for ${request.logLabel} (opId=$opId); " +
                            "peer closed during cancel race: ${ex.cause?.message ?: ex.message}",
                    cause = ex,
                )
            }
            throw unwrapExecutionFailure(opId, ex)
        } finally {
            pending.remove(opId)
        }
    }

    private fun cancelDueToInterrupt(
        opId: Long,
        request: AgentRequest,
        cause: Exception,
    ): SpectreAgentException {
        pending.remove(opId)
        runCatching { cancel(opId) }
        Thread.currentThread().interrupt()
        return SpectreAgentException(
            category = AgentErrorCategory.Cancelled,
            message = "Interrupted while waiting for ${request.logLabel} (opId=$opId)",
            cause = cause,
        )
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
            writeFrameInterruptSafe(WireCodec.encode(frame))
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
        var terminal: Exception =
            EOFException("Agent closed the connection while waiting for a response")
        // Framing/I/O failures are not a closed set of types (timeout, EOF, channel close, …).
        @Suppress("TooGenericExceptionCaught")
        try {
            while (!closed.get()) {
                // Idle between responses is allowed; mid-frame stalls time out.
                val bytes =
                    FrameIoDeadline.readFrameAllowingIdle(input, channel, frameIoTimeoutMs) ?: break
                dispatchOpResponse(bytes)
            }
        } catch (ex: Exception) {
            // Channel closed, I/O timeout, or transport error — complete outstanding futures.
            terminal =
                if (FrameIoDeadline.isTimeout(ex)) {
                    FrameIoDeadline.asTimeoutIoException(ex)
                } else {
                    EOFException(
                            "Agent closed the connection while waiting for a response: ${ex.message}"
                        )
                        .apply { initCause(ex) }
                }
        } finally {
            failAllPending(terminal)
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

    /**
     * SocketChannel is an InterruptibleChannel: a write from a thread that is interrupted (status
     * already set, or interrupt arriving mid-write) throws ClosedByInterruptException and closes
     * the shared client socket. Clearing interrupt on the caller around the write still races —
     * macOS can deliver the interrupt during `SocketChannel.write` on the cancel path, after which
     * a later op on the same session fails with ClosedChannelException.
     *
     * All writes therefore run on [writer], which callers never interrupt. The caller waits
     * uninterruptibly for the write to finish and then restores interrupt status so [send] can
     * still report Cancelled without closing the UDS.
     */
    private fun writeFrameInterruptSafe(payload: ByteArray) {
        val task =
            try {
                writer.submit<Unit> { writeFrameOnWriterThread(payload) }
            } catch (ex: RejectedExecutionException) {
                throw IOException("IpcClient writer is shut down", ex)
            }
        awaitWriter(task)
    }

    private fun writeFrameOnWriterThread(payload: ByteArray) {
        // A stale flag on the pooled writer must not close the shared channel.
        Thread.interrupted()
        synchronized(writeLock) {
            FrameIoDeadline.withTimeout(channel, frameIoTimeoutMs) {
                Framing.writeFrame(output, payload)
            }
        }
    }

    private fun awaitWriter(task: Future<*>) {
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    task.get()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (ex: ExecutionException) {
                    throw unwrapWriteFailure(ex)
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun unwrapWriteFailure(ex: ExecutionException): IOException {
        val cause = ex.cause
        return when (cause) {
            is IOException -> cause
            null -> IOException("IPC write failed: ${ex.message}", ex)
            else -> IOException("IPC write failed: ${cause.message}", cause)
        }
    }

    /** Bare (pre-envelope) exchange used only for Hello / best-effort Detach on failed Hello. */
    @Throws(IOException::class)
    private fun exchangeBare(request: AgentRequest): AgentResponse {
        writeFrameInterruptSafe(WireCodec.encode(request))
        val responseBytes =
            FrameIoDeadline.withTimeout(channel, frameIoTimeoutMs) { Framing.readFrame(input) }
                ?: throw EOFException(
                    "Agent closed the connection before sending a response to ${request.logLabel}"
                )
        return WireCodec.decodeResponse(responseBytes)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        // Drop the socket only — Detach is explicit agent teardown, not implied by close.
        // Do not shutdownNow() the writer: interrupting it would close the shared channel
        // if a write is still in flight. Closing the socket unblocks a wedged write.
        runCatching { channel.close() }
        writer.shutdown()
        runCatching { writer.awaitTermination(WRITER_JOIN_MS, TimeUnit.MILLISECONDS) }
        runCatching { readerThread.join(READER_JOIN_MS) }
        failAllPending(EOFException("IpcClient closed"))
    }

    private fun clientWaitMs(deadlineEpochMs: Long): Long {
        val remaining = deadlineEpochMs - System.currentTimeMillis()
        // Always pad so a server-side timeout response can still arrive after the deadline.
        return remaining.coerceAtLeast(0L) + ELAPSED_DEADLINE_GRACE_MS
    }

    private companion object {
        /** Floor so a server-side timeout/cancel response can arrive over UDS. */
        const val ELAPSED_DEADLINE_GRACE_MS: Long = 5_000
        const val CANCEL_ACK_WAIT_MS: Long = 5_000
        const val READER_JOIN_MS: Long = 1_000
        const val WRITER_JOIN_MS: Long = 1_000
    }
}
