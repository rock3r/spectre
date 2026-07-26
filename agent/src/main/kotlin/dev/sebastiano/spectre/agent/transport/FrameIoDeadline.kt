@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channel
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded I/O deadlines for agent UDS framing.
 *
 * Unix-domain [java.nio.channels.SocketChannel] has no portable `SO_TIMEOUT`. A scheduled closer
 * cancels a stuck read/write by closing the channel; the blocked stream call then fails and we map
 * that to [SocketTimeoutException].
 *
 * **Idle vs mid-frame:** Waiting for the *next* frame's first header byte is unbounded (healthy
 * sessions may be idle longer than the budget). Once any header byte arrives, the remainder of that
 * frame must complete within [DEFAULT_TIMEOUT_MS] or the peer is treated as wedged.
 */
internal object FrameIoDeadline {
    /** Default mid-frame / write budget for production attach sessions. */
    const val DEFAULT_TIMEOUT_MS: Long = 30_000L

    // removeOnCancelPolicy avoids retaining every successful op's cancelled 30s deadline
    // task in the delayed queue under sustained IPC throughput (Codex P2).
    private val scheduler: ScheduledThreadPoolExecutor =
        ScheduledThreadPoolExecutor(1) { r ->
                Thread(r, "spectre-ipc-io-deadline").apply { isDaemon = true }
            }
            .also { it.removeOnCancelPolicy = true }

    private enum class RunState {
        RUNNING,
        SUCCEEDED,
        TIMED_OUT,
    }

    /**
     * Runs [block] with a wall-clock deadline. If [timeoutMs] elapses before [block] returns,
     * [channel] is closed and a [SocketTimeoutException] is thrown.
     *
     * Pass [timeoutMs] `<= 0` to disable the deadline (tests only).
     */
    fun <T> withTimeout(channel: Channel, timeoutMs: Long, block: () -> T): T {
        if (timeoutMs <= 0L) return block()
        val state = AtomicReference(RunState.RUNNING)
        val future =
            scheduler.schedule(
                {
                    if (state.compareAndSet(RunState.RUNNING, RunState.TIMED_OUT)) {
                        runCatching { channel.close() }
                    }
                },
                timeoutMs,
                TimeUnit.MILLISECONDS,
            )
        // block may throw any I/O / framing failure; map only when the closer fired.
        @Suppress("TooGenericExceptionCaught")
        try {
            val result = block()
            if (!state.compareAndSet(RunState.RUNNING, RunState.SUCCEEDED)) {
                // Timer won after block returned but before we claimed success.
                throw timeoutException(timeoutMs, null)
            }
            return result
        } catch (ex: Exception) {
            if (state.get() == RunState.TIMED_OUT) throw timeoutException(timeoutMs, ex)
            state.compareAndSet(RunState.RUNNING, RunState.SUCCEEDED)
            throw ex
        } finally {
            future.cancel(false)
            state.compareAndSet(RunState.RUNNING, RunState.SUCCEEDED)
        }
    }

    /**
     * Read one frame. Waiting for the first header byte is unbounded (idle sessions). Once any
     * header byte is received, the rest of the frame must complete within [timeoutMs].
     */
    fun readFrameAllowingIdle(input: InputStream, channel: Channel, timeoutMs: Long): ByteArray? {
        val first = input.read()
        if (first == -1) return null
        return withTimeout(channel, timeoutMs) {
            val header = ByteArray(HEADER_BYTES)
            header[0] = first.toByte()
            readFully(input, header, offset = 1, length = HEADER_BYTES - 1)
            val frameLength = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
            check(frameLength in 0..MAX_FRAME_BYTES) {
                val headerDump = header.joinToString(",") { it.toInt().toString() }
                "Invalid frame length $frameLength (header bytes: $headerDump)"
            }
            if (frameLength == 0) return@withTimeout ByteArray(0)
            val payload = ByteArray(frameLength)
            readFully(input, payload, offset = 0, length = frameLength)
            payload
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

    private fun readFully(input: InputStream, buf: ByteArray, offset: Int, length: Int) {
        var read = 0
        while (read < length) {
            val r = input.read(buf, offset + read, length - read)
            if (r == -1) {
                throw EOFException("Unexpected EOF after $read of $length frame bytes")
            }
            read += r
        }
    }

    private const val HEADER_BYTES = 4
}
