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
        val inFlight = ConcurrentHashMap<Long, Future<*>>()
        val writeLock = Any()
        try {
            serveOps(input, output, workers, deadlineScheduler, inFlight, writeLock)
        } finally {
            inFlight.values.forEach { it.cancel(true) }
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
        inFlight: ConcurrentHashMap<Long, Future<*>>,
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
        inFlight: ConcurrentHashMap<Long, Future<*>>,
    ) {
        // Atomic remove claims write ownership for this opId; the worker only writes if it still
        // owns the map entry (Bugbot: cancel must not race a second response for the same op).
        val cancelled = inFlight.remove(cancel.opId)
        cancelled?.cancel(/* mayInterruptIfRunning= */ true)
        if (cancelled != null) {
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
        writeOpResponse(output, writeLock, cancelFrameOpId, AgentResponse.Ok)
    }

    private fun handleDetach(
        opId: Long,
        output: OutputStream,
        writeLock: Any,
        inFlight: ConcurrentHashMap<Long, Future<*>>,
    ) {
        inFlight.values.forEach { it.cancel(true) }
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
        inFlight: ConcurrentHashMap<Long, Future<*>>,
        output: OutputStream,
        writeLock: Any,
    ) {
        // Register a placeholder before submit so cancel cannot race past an empty map.
        val placeholder = CompletableFuturePlaceholder()
        inFlight[op.opId] = placeholder
        val future = workers.submit {
            if (Thread.currentThread().isInterrupted) {
                // Cancel may already own the slot; do not write a second response.
                inFlight.remove(op.opId)
                return@submit
            }
            val response = executeOp(body, op.deadlineEpochMs)
            // Claim write ownership: only the winner of remove() may write the response.
            if (inFlight.remove(op.opId) != null) {
                writeOpResponse(output, writeLock, op.opId, response)
            }
        }
        // Replace placeholder with the real Future so cancel interrupts the worker.
        if (!inFlight.replace(op.opId, placeholder, future)) {
            // Cancel already claimed ownership (and wrote cancelled) — interrupt the work.
            future.cancel(true)
            return
        }
        scheduleDeadline(op, deadlineScheduler, inFlight, output, writeLock)
    }

    /**
     * When [OpRequest.deadlineEpochMs] is in the future, interrupt the worker at the deadline and
     * claim the response as taxonomy `timeout` (Codex P2: do not wait for a blocking handler to
     * return).
     */
    private fun scheduleDeadline(
        op: OpRequest,
        deadlineScheduler: ScheduledExecutorService,
        inFlight: ConcurrentHashMap<Long, Future<*>>,
        output: OutputStream,
        writeLock: Any,
    ) {
        val deadline = op.deadlineEpochMs ?: return
        val delayMs = deadline - System.currentTimeMillis()
        if (delayMs <= 0L) return // executeOp already handles already-elapsed deadlines
        deadlineScheduler.schedule(
            {
                val f = inFlight.remove(op.opId) ?: return@schedule
                f.cancel(/* mayInterruptIfRunning= */ true)
                writeOpResponse(
                    output,
                    writeLock,
                    op.opId,
                    AgentResponse.Error(
                        message = "Deadline elapsed during op",
                        category = AgentErrorCategory.Timeout.wireName,
                    ),
                )
            },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private fun executeOp(request: AgentRequest, deadlineEpochMs: Long?): AgentResponse {
        if (deadlineEpochMs != null && System.currentTimeMillis() > deadlineEpochMs) {
            return AgentResponse.Error(
                message = "Deadline already elapsed before op started",
                category = AgentErrorCategory.Timeout.wireName,
            )
        }
        return try {
            val response = handler.handle(request)
            if (deadlineEpochMs != null && System.currentTimeMillis() > deadlineEpochMs) {
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
     * Stand-in [Future] registered before [ExecutorService.submit] so a concurrent cancel always
     * finds the op id. [cancel] is a no-op until the real future replaces this entry.
     */
    private class CompletableFuturePlaceholder : Future<Unit> {
        private val cancelled = AtomicBoolean(false)

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean =
            cancelled.compareAndSet(false, true)

        override fun isCancelled(): Boolean = cancelled.get()

        override fun isDone(): Boolean = cancelled.get()

        override fun get(): Unit = Unit

        override fun get(timeout: Long, unit: TimeUnit): Unit = Unit
    }

    private companion object {
        const val WORKER_SHUTDOWN_SEC: Long = 2
    }
}
