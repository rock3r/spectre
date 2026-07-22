package dev.sebastiano.spectre.agent.transport

import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ScheduledExecutorService
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
) {
    fun run(input: InputStream, output: OutputStream) {
        val workers: ExecutorService = Executors.newCachedThreadPool { r ->
            Thread(r, "spectre-agent-op-worker").apply { isDaemon = true }
        }
        val deadlineScheduler: ScheduledExecutorService =
            Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "spectre-agent-deadline").apply { isDaemon = true }
            }
        val inFlight = ConcurrentHashMap<Long, OpSlot>()
        val writeLock = Any()
        try {
            serveOps(input, output, workers, deadlineScheduler, inFlight, writeLock)
        } finally {
            inFlight.values.forEach { it.abortRunningWork() }
            inFlight.clear()
            deadlineScheduler.shutdownNow()
            workers.shutdownNow()
            runCatching { workers.awaitTermination(WORKER_SHUTDOWN_SEC, TimeUnit.SECONDS) }
        }
    }

    private fun serveOps(
        input: InputStream,
        output: OutputStream,
        workers: ExecutorService,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        writeLock: Any,
    ) {
        while (running.get()) {
            val requestBytes = Framing.readFrame(input) ?: return
            val op = decodeOpOrReport(requestBytes, output, writeLock) ?: continue
            when (val body = op.body) {
                is AgentRequest.Cancel -> handleCancel(body, op.opId, output, writeLock, inFlight)
                AgentRequest.Detach -> {
                    handleDetach(op.opId, output, writeLock, inFlight)
                    return
                }
                else ->
                    dispatchOp(op, body, workers, deadlineScheduler, inFlight, output, writeLock)
            }
        }
    }

    private fun decodeOpOrReport(
        requestBytes: ByteArray,
        output: OutputStream,
        writeLock: Any,
    ): OpRequest? =
        try {
            WireCodec.decodeOpRequest(requestBytes)
        } catch (ex: kotlinx.serialization.SerializationException) {
            // Prefer the real opId so the client's pending future unblocks with taxonomy error,
            // not a 120s timeout hang (Bugbot: decode errors must correlate).
            val opId =
                runCatching { WireCodec.decodeOpRequestShell(requestBytes).opId }.getOrDefault(-1L)
            val category =
                if (WireCodec.isUnknownDiscriminator(ex)) {
                    AgentErrorCategory.UnsupportedOperation
                } else {
                    AgentErrorCategory.ProtocolMismatch
                }
            writeOpResponse(
                output,
                writeLock,
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
        inFlight: ConcurrentHashMap<Long, OpSlot>,
    ) {
        val slot = inFlight.remove(cancel.opId)
        if (slot != null) {
            slot.abortRunningWork()
            if (slot.tryClaimResponse()) {
                writeOpResponse(
                    output,
                    writeLock,
                    cancel.opId,
                    AgentResponse.Error(
                        message = "Operation cancelled",
                        category = AgentErrorCategory.Cancelled.wireName,
                    ),
                )
            }
        }
        writeOpResponse(output, writeLock, cancelFrameOpId, AgentResponse.Ok)
    }

    private fun handleDetach(
        opId: Long,
        output: OutputStream,
        writeLock: Any,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
    ) {
        inFlight.values.forEach { it.abortRunningWork() }
        inFlight.clear()
        try {
            writeOpResponse(output, writeLock, opId, AgentResponse.Detached)
        } finally {
            running.set(false)
            onDetach()
        }
    }

    private fun dispatchOp(
        op: OpRequest,
        body: AgentRequest,
        workers: ExecutorService,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        output: OutputStream,
        writeLock: Any,
    ) {
        val slot = OpSlot()
        inFlight[op.opId] = slot
        val future = workers.submit {
            if (slot.isAborted || Thread.currentThread().isInterrupted) {
                inFlight.remove(op.opId, slot)
                return@submit
            }
            val response = executeOp(body, op.deadlineEpochMs)
            // Only the winner of tryClaimResponse may write (cancel/deadline may have won).
            if (slot.tryClaimResponse()) {
                inFlight.remove(op.opId, slot)
                writeOpResponse(output, writeLock, op.opId, response)
            } else {
                inFlight.remove(op.opId, slot)
            }
        }
        slot.attachFuture(future)
        // If cancel already aborted before attachFuture, interrupt the just-started work.
        if (slot.isAborted) {
            future.cancel(true)
            inFlight.remove(op.opId, slot)
            return
        }
        scheduleDeadline(op, slot, deadlineScheduler, inFlight, output, writeLock)
    }

    /**
     * When [OpRequest.deadlineEpochMs] is in the future, interrupt the worker at the deadline and
     * claim the response as taxonomy `timeout`.
     */
    private fun scheduleDeadline(
        op: OpRequest,
        slot: OpSlot,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, OpSlot>,
        output: OutputStream,
        writeLock: Any,
    ) {
        val deadline = op.deadlineEpochMs ?: return
        val delayMs = deadline - System.currentTimeMillis()
        val fireTimeout = {
            if (inFlight.remove(op.opId, slot)) {
                slot.abortRunningWork()
                if (slot.tryClaimResponse()) {
                    writeOpResponse(
                        output,
                        writeLock,
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
            fireTimeout()
            return
        }
        deadlineScheduler.schedule(fireTimeout, delayMs, TimeUnit.MILLISECONDS)
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
        opId: Long,
        body: AgentResponse,
    ) {
        synchronized(writeLock) {
            Framing.writeFrame(output, WireCodec.encode(OpResponse(opId = opId, body = body)))
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

        val isAborted: Boolean
            get() = aborted.get()

        fun attachFuture(f: Future<*>) {
            future.set(f)
            if (aborted.get()) {
                f.cancel(/* mayInterruptIfRunning= */ true)
            }
        }

        fun abortRunningWork() {
            aborted.set(true)
            future.get()?.cancel(/* mayInterruptIfRunning= */ true)
        }

        fun tryClaimResponse(): Boolean = responseClaimed.compareAndSet(false, true)
    }

    private companion object {
        const val WORKER_SHUTDOWN_SEC: Long = 2
    }
}
