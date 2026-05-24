package dev.sebastiano.spectre.agent.transport

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Default upper bound on a single frame's payload. Screenshots are the bulkiest reasonable case.
 */
internal const val MAX_FRAME_BYTES: Int = 16 * 1024 * 1024

/**
 * Length-prefixed binary framing for the agent's IPC wire protocol.
 *
 * Wire format: `[4-byte big-endian payload length][payload bytes]`. The 4-byte header carries a
 * non-negative `int` length capped at [MAX_FRAME_BYTES]. Zero-length payloads are legal (they
 * serialize a `data object` to an empty CBOR map sometimes — the codec layer handles them).
 *
 * Streams are not closed by these functions; that's the caller's responsibility.
 *
 * The functions intentionally use [InputStream] / [OutputStream] rather than NIO channels so the
 * same code drives Unix-domain-socket connections, pipe-pair tests, and any future transport that
 * produces stream-like endpoints.
 */
internal object Framing {
    /**
     * Writes one frame: the 4-byte big-endian header followed by [payload]. Flushes the stream so
     * the receiver doesn't block waiting for buffered bytes.
     */
    @Throws(java.io.IOException::class)
    fun writeFrame(output: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_FRAME_BYTES) {
            "Frame payload size ${payload.size} exceeds MAX_FRAME_BYTES=$MAX_FRAME_BYTES"
        }
        val header =
            ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(payload.size)
                .array()
        output.write(header)
        if (payload.isNotEmpty()) output.write(payload)
        output.flush()
    }

    /**
     * Reads one frame from [input]. Returns the payload bytes, or `null` on clean EOF (zero bytes
     * read before any header byte arrived). Throws [EOFException] on a truncated header or payload
     * mid-frame, and [IllegalStateException] on negative or over-cap lengths.
     */
    @Throws(java.io.IOException::class)
    fun readFrame(input: InputStream): ByteArray? {
        val header = readFullyOrNull(input, HEADER_BYTES) ?: return null
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        check(length in 0..MAX_FRAME_BYTES) {
            "Invalid frame length $length (header bytes: ${header.joinToString(",") { it.toInt().toString() }})"
        }
        if (length == 0) return ByteArray(0)
        return readFully(input, length)
    }

    private const val HEADER_BYTES = 4

    private fun readFullyOrNull(input: InputStream, n: Int): ByteArray? {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) {
                return if (read == 0) null
                else throw EOFException("Unexpected EOF after $read of $n header bytes")
            }
            read += r
        }
        return buf
    }

    private fun readFully(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = input.read(buf, read, n - read)
            if (r == -1) throw EOFException("Unexpected EOF after $read of $n payload bytes")
            read += r
        }
        return buf
    }
}
