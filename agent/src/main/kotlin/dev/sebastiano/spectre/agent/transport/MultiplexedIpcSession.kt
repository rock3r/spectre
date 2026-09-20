package dev.sebastiano.spectre.agent.transport

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Post-Hello multiplexed IPC session (#200): accept thread only reads/dispatches; work runs on a
 * worker pool so long ops do not block cancel/detach.
 *
 * Client EOF (socket close without Detach) ends the session only — [running] stays true so another
 * client can connect. Explicit Detach tears the agent down via [onDetach].
 */
internal class MultiplexedIpcSession(
    private val handler: AgentRequestHandler,
    private val running: AtomicBoolean,
    private val onDetach: () -> Unit,
    private val channel: java.nio.channels.Channel,
    private val frameIoTimeoutMs: Long = FrameIoDeadline.DEFAULT_TIMEOUT_MS,
    private val inputWorkers: CrossSessionInputWorkers,
) {
    fun run(input: InputStream, output: OutputStream) {
        // Bound threads *and* queue so a buggy client cannot OOM the agent with enqueued ops.
        val workers: ExecutorService =
            ThreadPoolExecutor(
                /* corePoolSize= */ MAX_OP_WORKERS,
                /* maximumPoolSize= */ MAX_OP_WORKERS,
                /* keepAliveTime= */ 0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(MAX_OP_QUEUE),
                { r -> Thread(r, "spectre-agent-op-worker").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )
        val inputWorker: ExecutorService =
            ThreadPoolExecutor(
                /* corePoolSize= */ 1,
                /* maximumPoolSize= */ 1,
                /* keepAliveTime= */ 0L,
                TimeUnit.MILLISECONDS,
                LinkedBlockingQueue(MAX_INPUT_QUEUE),
                { r -> Thread(r, "spectre-agent-input-worker").apply { isDaemon = true } },
                ThreadPoolExecutor.AbortPolicy(),
            )
        val deadlineScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "spectre-agent-deadline").apply { isDaemon = true }
            }
        val inFlight = ConcurrentHashMap<Long, OpSlot>()
        val writeLock = Any()
        val detachRequested = AtomicBoolean(false)
        val detachOpId = AtomicReference<Long?>()
        val responseWriter: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "spectre-agent-ipc-writer").apply { isDaemon = true }
        }
        inputWorkers.register(inputWorker)
        try {
            serveOps(
                input,
                output,
                workers,
                inputWorker,
                deadlineScheduler,
                inFlight,
                writeLock,
                responseWriter,
                detachRequested,
                detachOpId,
            )
        } finally {
            inFlight.values.forEach { it.abortRunningWork() }
            inFlight.clear()
            deadlineScheduler.shutdownNow()
            workers.shutdownNow()
            inputWorker.shutdownNow()
            runCatching { workers.awaitTermination(WORKER_SHUTDOWN_SEC, TimeUnit.SECONDS) }
            val inputWaitInterrupted =
                if (detachRequested.get()) {
                    inputWorkers.awaitAllTerminated()
                } else {
                    inputWorkers.releaseAfterTermination(inputWorker)
                    false
                }
            try {
                if (detachRequested.get()) {
                    onDetach()
                    writeOpResponse(
                        output,
                        writeLock,
                        responseWriter,
                        requireNotNull(detachOpId.get()),
                        AgentResponse.Detached,
                    )
                }
            } finally {
                // Never shutdownNow(): interrupting the writer would close the shared UDS.
                responseWriter.shutdown()
                runCatching {
                    responseWriter.awaitTermination(WORKER_SHUTDOWN_SEC, TimeUnit.SECONDS)
                }
                if (inputWaitInterrupted) Thread.currentThread().interrupt()
            }
        }
    }

    private fun serveOps(
        input: InputStream,
        output: OutputStream,
        workers: ExecutorService,
        inputWorker: ExecutorService,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        writeLock: Any,
        responseWriter: ExecutorService,
        detachRequested: AtomicBoolean,
        detachOpId: AtomicReference<Long?>,
    ) {
        while (running.get()) {
            // Channel-close deadlines may surface as SocketTimeoutException or as a
            // ClosedChannelException / AsynchronousCloseException on some platforms.
            @Suppress("TooGenericExceptionCaught")
            val requestBytes =
                try {
                    // Idle between requests is allowed; mid-frame stalls time out.
                    FrameIoDeadline.readFrameAllowingIdle(input, channel, frameIoTimeoutMs)
                        ?: return
                } catch (ex: Exception) {
                    if (FrameIoDeadline.isTimeout(ex) || !channel.isOpen) {
                        System.err.println(
                            "[spectre-agent] frame I/O timed out or peer closed " +
                                "(${ex.javaClass.simpleName}: ${ex.message}); closing connection"
                        )
                        return
                    }
                    throw ex
                }
            val op = decodeOpOrReport(requestBytes, output, writeLock, responseWriter) ?: continue
            when (val body = op.body) {
                is AgentRequest.Cancel ->
                    handleCancel(body, op.opId, output, writeLock, responseWriter, inFlight)
                AgentRequest.Detach -> {
                    handleDetach(op.opId, inFlight, detachRequested, detachOpId)
                    return
                }
                else ->
                    dispatchOp(
                        op,
                        body,
                        if (body.requiresInputLane) inputWorker else workers,
                        deadlineScheduler,
                        inFlight,
                        output,
                        writeLock,
                        responseWriter,
                    )
            }
        }
    }

    private fun decodeOpOrReport(
        requestBytes: ByteArray,
        output: OutputStream,
        writeLock: Any,
        responseWriter: ExecutorService,
    ): OpRequest? =
        try {
            WireCodec.decodeOpRequest(requestBytes)
        } catch (ex: kotlinx.serialization.SerializationException) {
            // Prefer the real opId so the client's pending future unblocks with taxonomy error,
            // not a 120s timeout hang (Bugbot: decode errors must correlate).
            val opId = runCatching {
                WireCodec.decodeOpRequestShell(requestBytes).opId
            }
                .getOrDefault(-1L)
            val category =
                if (WireCodec.isUnknownDiscriminator(ex)) {
                    AgentErrorCategory.UnsupportedOperation
                } else {
                    AgentErrorCategory.ProtocolMismatch
                }
            writeOpResponse(
                output,
                writeLock,
                responseWriter,
                opId,
                AgentResponse.Error(
                    message = "Malformed or unsupported op frame: ${ex.message}",
                    category = category.wireName,
                ),
            )
            null
        }

    private fun handleCancel(
        cancel: AgentRequest.Cancel,
        cancelFrameOpId: Long,
        output: OutputStream,
        writeLock: Any,
        responseWriter: ExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
    ) {
        val slot = inFlight.remove(cancel.opId)
        if (slot != null) {
            slot.cancelDeadlineTask()
            slot.abortRunningWork()
            if (slot.tryClaimResponse()) {
                writeOpResponse(
                    output,
                    writeLock,
                    responseWriter,
                    cancel.opId,
                    AgentResponse.Error(
                        message = "Operation cancelled",
                        category = AgentErrorCategory.Cancelled.wireName,
                    ),
                )
            }
        }
        writeOpResponse(output, writeLock, responseWriter, cancelFrameOpId, AgentResponse.Ok)
    }

    private fun handleDetach(
        opId: Long,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        detachRequested: AtomicBoolean,
        detachOpId: AtomicReference<Long?>,
    ) {
        inFlight.values.forEach { it.abortRunningWork() }
        inFlight.clear()
        detachOpId.set(opId)
        running.set(false)
        detachRequested.set(true)
    }

    private fun dispatchOp(
        op: OpRequest,
        body: AgentRequest,
        executor: ExecutorService,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        output: OutputStream,
        writeLock: Any,
        responseWriter: ExecutorService,
    ) {
        val slot = OpSlot()
        inFlight[op.opId] = slot
        val future =
            try {
                executor.submit {
                    if (slot.isAborted || Thread.currentThread().isInterrupted) {
                        if (slot.tryClaimResponse()) {
                            inFlight.remove(op.opId, slot)
                            writeOpResponse(
                                output,
                                writeLock,
                                responseWriter,
                                op.opId,
                                AgentResponse.Error(
                                    message = "Operation cancelled",
                                    category = AgentErrorCategory.Cancelled.wireName,
                                ),
                            )
                        } else {
                            inFlight.remove(op.opId, slot)
                        }
                        Thread.interrupted()
                        return@submit
                    }
                    val response = executeOp(body, op.deadlineEpochMs)
                    // Only the winner of tryClaimResponse may write (cancel/deadline may have won).
                    slot.cancelDeadlineTask()
                    // Cancel/deadline may have interrupted this worker mid-op. Do not write a
                    // success payload while interrupted — NIO SocketChannel is interruptible and
                    // a write with interrupt status set can close the *shared* client channel
                    // (ClosedByInterruptException), killing subsequent ops (LongOp Ping flake).
                    if (slot.isAborted || Thread.currentThread().isInterrupted) {
                        // Prefer taxonomy cancelled when we still own the response claim.
                        if (slot.tryClaimResponse()) {
                            inFlight.remove(op.opId, slot)
                            writeOpResponse(
                                output,
                                writeLock,
                                responseWriter,
                                op.opId,
                                AgentResponse.Error(
                                    message = "Operation cancelled",
                                    category = AgentErrorCategory.Cancelled.wireName,
                                ),
                            )
                        } else {
                            inFlight.remove(op.opId, slot)
                        }
                        // Clear poison interrupt so this worker cannot close the shared channel
                        // if anything runs after the claim path (thread returns to the pool).
                        Thread.interrupted()
                        return@submit
                    }
                    if (slot.tryClaimResponse()) {
                        inFlight.remove(op.opId, slot)
                        writeOpResponse(output, writeLock, responseWriter, op.opId, response)
                    } else {
                        inFlight.remove(op.opId, slot)
                    }
                }
            } catch (_: RejectedExecutionException) {
                inFlight.remove(op.opId, slot)
                writeOpResponse(
                    output,
                    writeLock,
                    responseWriter,
                    op.opId,
                    AgentResponse.Error(
                        message = "Too many concurrent operations (queue full)",
                        category = AgentErrorCategory.InternalError.wireName,
                    ),
                )
                return
            }
        slot.attachFuture(future)
        // If cancel already aborted before attachFuture, interrupt the just-started work.
        if (slot.isAborted) {
            future.cancel(true)
            inFlight.remove(op.opId, slot)
            return
        }
        slot.attachDeadlineTask(
            scheduleDeadline(
                op,
                slot,
                deadlineScheduler,
                inFlight,
                output,
                writeLock,
                responseWriter,
            )
        )
    }

    /**
     * When [OpRequest.deadlineEpochMs] is in the future, interrupt the worker at the deadline and
     * claim the response as taxonomy `timeout`. Returns the scheduled task so the slot can cancel
     * it when the op completes early.
     */
    private fun scheduleDeadline(
        op: OpRequest,
        slot: OpSlot,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        output: OutputStream,
        writeLock: Any,
        responseWriter: ExecutorService,
    ): ScheduledFuture<*>? {
        val deadline = op.deadlineEpochMs ?: return null
        val delayMs = deadline - System.currentTimeMillis()
        val fireTimeout = Runnable {
            if (inFlight.remove(op.opId, slot)) {
                slot.abortRunningWork()
                if (slot.tryClaimResponse()) {
                    writeOpResponse(
                        output,
                        writeLock,
                        responseWriter,
                        op.opId,
                        AgentResponse.Error(
                            message = "Deadline elapsed during op",
                            category = AgentErrorCategory.Timeout.wireName,
                        ),
                    )
                }
            }
        }
        if (delayMs <= 0L) {
            fireTimeout.run()
            return null
        }
        return deadlineScheduler.schedule(fireTimeout, delayMs, TimeUnit.MILLISECONDS)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeOp(request: AgentRequest, deadlineEpochMs: Long?): AgentResponse {
        if (deadlineEpochMs != null && System.currentTimeMillis() >= deadlineEpochMs) {
            return AgentResponse.Error(
                message = "Deadline already elapsed before op started",
                category = AgentErrorCategory.Timeout.wireName,
            )
        }
        return try {
            val response = handler.handle(request)
            if (deadlineEpochMs != null && System.currentTimeMillis() >= deadlineEpochMs) {
                AgentResponse.Error(
                    message = "Deadline elapsed during op",
                    category = AgentErrorCategory.Timeout.wireName,
                )
            } else {
                response
            }
        } catch (ex: InterruptedException) {
            Thread.currentThread().interrupt()
            AgentResponse.Error(
                message = "Operation cancelled",
                category = AgentErrorCategory.Cancelled.wireName,
            )
        } catch (ex: java.nio.channels.ClosedByInterruptException) {
            Thread.currentThread().interrupt()
            AgentResponse.Error(
                message = "Operation cancelled: ${ex.javaClass.simpleName}",
                category = AgentErrorCategory.Cancelled.wireName,
            )
        } catch (ex: Exception) {
            val category =
                when (ex) {
                    is java.util.concurrent.TimeoutException -> AgentErrorCategory.Timeout
                    is IllegalArgumentException -> AgentErrorCategory.InvalidSelector
                    is NoSuchElementException -> AgentErrorCategory.NodeNotFound
                    else -> AgentErrorCategory.InternalError
                }
            AgentResponse.Error(
                message = "${ex.javaClass.simpleName}: ${ex.message ?: "<no message>"}",
                category = category.wireName,
            )
        }
    }

    private fun writeOpResponse(
        output: OutputStream,
        writeLock: Any,
        responseWriter: ExecutorService,
        opId: Long,
        body: AgentResponse,
    ) {
        val payload = WireCodec.encode(OpResponse(opId = opId, body = body))
        val budget = FrameLimits.maxFrameBytes
        val toWrite =
            if (payload.size <= budget) {
                payload
            } else {
                // Fail closed with a small taxonomy error instead of throwing mid-write (#204).
                WireCodec.encode(
                    OpResponse(
                        opId = opId,
                        body =
                            AgentResponse.Error(
                                message =
                                    "Response payload size ${payload.size} exceeds " +
                                        "MAX_FRAME_BYTES=$budget",
                                category = AgentErrorCategory.PayloadTooLarge.wireName,
                            ),
                    )
                )
            }
        // SocketChannel is an InterruptibleChannel: a write from an interrupted worker
        // (status set, or interrupt arriving mid-write) closes the shared client socket.
        // Clearing interrupt on the worker still races — hop the write onto [responseWriter],
        // which cancel/deadline never interrupt.
        val task =
            try {
                responseWriter.submit<Unit> {
                    Thread.interrupted()
                    synchronized(writeLock) {
                        FrameIoDeadline.withTimeout(channel, frameIoTimeoutMs) {
                            Framing.writeFrame(output, toWrite)
                        }
                    }
                }
            } catch (ex: RejectedExecutionException) {
                throw java.io.IOException("Session writer is shut down", ex)
            }
        awaitWriter(task)
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
                    val cause = ex.cause
                    throw when (cause) {
                        is java.io.IOException -> cause
                        null -> java.io.IOException("IPC write failed: ${ex.message}", ex)
                        else -> java.io.IOException("IPC write failed: ${cause.message}", cause)
                    }
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    /**
     * Per-op coordination: abort flag for interrupt, future ref so cancel can interrupt after
     * submit, and single-winner response claim so cancel/worker/deadline never double-write.
     */
    private class OpSlot {
        private val aborted = AtomicBoolean(false)
        private val responseClaimed = AtomicBoolean(false)
        private val future = AtomicReference<Future<*>?>(null)
        private val deadlineTask = AtomicReference<ScheduledFuture<*>?>(null)

        val isAborted: Boolean
            get() = aborted.get()

        fun attachFuture(f: Future<*>) {
            future.set(f)
            if (aborted.get()) {
                f.cancel(/* mayInterruptIfRunning= */ true)
            }
        }

        fun attachDeadlineTask(task: ScheduledFuture<*>?) {
            if (task == null) return
            deadlineTask.set(task)
            // Op may already have completed/cancelled before the task was registered.
            if (responseClaimed.get() || aborted.get()) {
                task.cancel(/* mayInterruptIfRunning= */ false)
            }
        }

        fun cancelDeadlineTask() {
            deadlineTask.getAndSet(null)?.cancel(/* mayInterruptIfRunning= */ false)
        }

        fun abortRunningWork() {
            // If the worker already claimed the response slot it is about to write (or is
            // writing). Interrupting it mid-write can close the shared InterruptibleChannel
            // and kill subsequent ops — cancel already lost tryClaimResponse in that case.
            if (responseClaimed.get()) return
            aborted.set(true)
            future.get()?.cancel(/* mayInterruptIfRunning= */ true)
        }

        fun tryClaimResponse(): Boolean = responseClaimed.compareAndSet(false, true)
    }

    private companion object {
        const val WORKER_SHUTDOWN_SEC: Long = 2
        /** Max concurrent op workers per attached session (long op + cancel + headroom). */
        const val MAX_OP_WORKERS: Int = 8
        /** Max queued ops beyond the worker pool (reject when full). */
        const val MAX_OP_QUEUE: Int = 32
        /** Input requests wait here without consuming any general operation workers. */
        const val MAX_INPUT_QUEUE: Int = 32
    }
}

internal class CrossSessionInputWorkers {
    private val workers = ConcurrentHashMap.newKeySet<ExecutorService>()

    fun register(worker: ExecutorService) {
        check(workers.add(worker)) { "Input worker is already registered" }
    }

    fun releaseAfterTermination(worker: ExecutorService) {
        Thread.ofVirtual().name("spectre-agent-input-reaper").start {
            awaitTermination(worker)
            workers.remove(worker)
        }
    }

    fun awaitAllTerminated(): Boolean {
        var interrupted = false
        while (true) {
            val activeWorkers = workers.toList()
            if (activeWorkers.isEmpty()) return interrupted
            activeWorkers.forEach { worker ->
                if (awaitTermination(worker)) interrupted = true
                workers.remove(worker)
            }
        }
    }

    private fun awaitTermination(worker: ExecutorService): Boolean {
        var interrupted = false
        while (!worker.isTerminated) {
            try {
                worker.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        return interrupted
    }
}
