package dev.sebastiano.spectre.agent.transport

import java.io.IOException
import java.net.SocketTimeoutException
import java.nio.channels.Channel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded I/O deadlines for agent UDS framing.
 *
 * Unix-domain [java.nio.channels.SocketChannel] has no portable `SO_TIMEOUT`. A scheduled closer
 * cancels a stuck read/write by closing the channel; the blocked stream call then fails and we map
 * that to [SocketTimeoutException].
 */
internal object FrameIoDeadline {
    /** Default per-frame read/write budget for production attach sessions. */
    const val DEFAULT_TIMEOUT_MS: Long = 30_000L

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "spectre-ipc-io-deadline").apply { isDaemon = true }
        }

    /**
     * Runs [block] with a wall-clock deadline. If [timeoutMs] elapses before [block] returns,
     * [channel] is closed and a [SocketTimeoutException] is thrown (wrapping the I/O failure if one
     * surfaces).
     *
     * Pass [timeoutMs] `<= 0` to disable the deadline (tests only).
     */
    fun <T> withTimeout(channel: Channel, timeoutMs: Long, block: () -> T): T {
        if (timeoutMs <= 0L) return block()
        val timedOut = AtomicBoolean(false)
        val future =
            scheduler.schedule(
                {
                    if (timedOut.compareAndSet(false, true)) {
                        runCatching { channel.close() }
                    }
                },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        // block may throw any I/O / framing failure; map only the deadline-closed path.
        @Suppress("TooGenericExceptionCaught")
        try {
            return block()
        } catch (ex: Exception) {
            if (timedOut.get()) throw timeoutException(timeoutMs, ex)
            throw ex
        } finally {
            future.cancel(false)
        }
    }

    /** True when [error] (or its cause chain) is a frame-I/O deadline failure. */
    fun isTimeout(error: Throwable?): Boolean {
        var cur: Throwable? = error
        while (cur != null) {
            if (cur is SocketTimeoutException) return true
            cur = cur.cause
        }
        return false
    }

    fun asTimeoutIoException(error: Throwable): IOException =
        when (error) {
            is SocketTimeoutException -> error
            is IOException ->
                if (isTimeout(error)) error else timeoutException(DEFAULT_TIMEOUT_MS, error)
            else -> timeoutException(DEFAULT_TIMEOUT_MS, error)
        }

    private fun timeoutException(timeoutMs: Long, cause: Throwable?): SocketTimeoutException =
        SocketTimeoutException("Spectre agent IPC frame I/O timed out after ${timeoutMs}ms").also {
            if (cause != null) it.initCause(cause)
        }
}
