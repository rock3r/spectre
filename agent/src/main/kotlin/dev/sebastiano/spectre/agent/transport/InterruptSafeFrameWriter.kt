package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serializes UDS frame writes onto one daemon thread that callers never interrupt.
 *
 * [java.nio.channels.SocketChannel] is an InterruptibleChannel: a write from an interrupted thread
 * (or an interrupt arriving mid-write) throws ClosedByInterruptException **and closes the shared
 * socket**. `Executors.newSingleThreadExecutor` is easy to get wrong on that contract (default
 * factory threads are non-daemon; `shutdownNow()` interrupts the worker). This helper starts an
 * explicit daemon thread, waits uninterruptibly for each write, and only interrupts the worker
 * after [close] once the caller has already dropped the channel. Task admission is serialized with
 * shutdown so a racy [writeFrame] cannot enqueue after the worker exits and hang in
 * [awaitUninterruptibly].
 */
internal class InterruptSafeFrameWriter(
    threadName: String,
    private val performWrite: (ByteArray) -> Unit,
) : AutoCloseable {
    private val tasks = LinkedBlockingQueue<WriteTask>()
    private val closed = AtomicBoolean(false)
    /** Serializes [writeFrame] admission with [close] so a racy offer cannot outlive the worker. */
    private val admission = Any()
    private val thread =
        Thread(::loop, threadName).apply {
            isDaemon = true
            start()
        }

    @Throws(IOException::class)
    fun writeFrame(payload: ByteArray) {
        val task = WriteTask(payload)
        synchronized(admission) {
            if (closed.get()) throw IOException("IPC writer is shut down")
            if (!tasks.offer(task)) throw IOException("IPC writer is shut down")
        }
        awaitUninterruptibly(task)
    }

    override fun close() {
        synchronized(admission) {
            if (!closed.compareAndSet(false, true)) return
            // Wake take() after the caller has closed the channel so a blocked write unblocks
            // as ClosedChannelException rather than ClosedByInterruptException-on-an-open-socket.
            tasks.offer(POISON)
        }
        thread.interrupt()
        runCatching { thread.join(JOIN_MS) }
    }

    private fun loop() {
        Thread.interrupted()
        var task = takeNext()
        while (task != null) {
            runOne(task)
            task = takeNext()
        }
        failRemaining(IOException("IPC writer is shut down"))
    }

    private fun takeNext(): WriteTask? =
        try {
            val task = tasks.take()
            if (task === POISON) null else task
        } catch (_: InterruptedException) {
            null
        }

    @Suppress("TooGenericExceptionCaught") // performWrite is framing I/O; unblock the waiter.
    private fun runOne(task: WriteTask) {
        Thread.interrupted()
        try {
            performWrite(task.payload)
            task.complete()
        } catch (ex: Exception) {
            task.completeExceptionally(ex)
        }
    }

    private fun failRemaining(err: IOException) {
        var leftover: WriteTask? = tasks.poll()
        while (leftover != null) {
            if (leftover !== POISON) leftover.completeExceptionally(err)
            leftover = tasks.poll()
        }
    }

    private fun awaitUninterruptibly(task: WriteTask) {
        var interrupted = Thread.interrupted()
        try {
            while (true) {
                try {
                    task.future.get()
                    return
                } catch (_: InterruptedException) {
                    interrupted = true
                } catch (ex: java.util.concurrent.ExecutionException) {
                    throw unwrapWriteFailure(ex)
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    private fun unwrapWriteFailure(ex: java.util.concurrent.ExecutionException): IOException {
        val cause = ex.cause
        return when (cause) {
            is IOException -> cause
            null -> IOException("IPC write failed: ${ex.message}", ex)
            else -> IOException("IPC write failed: ${cause.message}", cause)
        }
    }

    private class WriteTask(val payload: ByteArray) {
        val future = CompletableFuture<Unit>()

        fun complete() {
            future.complete(Unit)
        }

        fun completeExceptionally(ex: Throwable) {
            future.completeExceptionally(ex)
        }
    }

    private companion object {
        val POISON = WriteTask(ByteArray(0))
        val JOIN_MS: Long = TimeUnit.SECONDS.toMillis(1)
    }
}
