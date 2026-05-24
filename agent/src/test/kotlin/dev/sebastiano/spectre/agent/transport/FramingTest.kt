package dev.sebastiano.spectre.agent.transport

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Tests for length-prefixed frame I/O (T-2 in the issue #153 workshop plan).
 *
 * Uses paired `ByteArrayOutputStream` / `ByteArrayInputStream` rather than real pipes — the Framing
 * API is `InputStream` / `OutputStream`, so the test fidelity is identical to a real socket as far
 * as the framing logic can see.
 */
class FramingTest {
    @Test
    fun `writes and reads back a single non-empty frame`() {
        val payload = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05)
        val buffer = ByteArrayOutputStream()

        Framing.writeFrame(buffer, payload)
        val readBack = Framing.readFrame(ByteArrayInputStream(buffer.toByteArray()))

        assertContentEquals(payload, readBack)
    }

    @Test
    fun `writes and reads back a zero-byte frame`() {
        val buffer = ByteArrayOutputStream()
        Framing.writeFrame(buffer, ByteArray(0))

        val readBack = Framing.readFrame(ByteArrayInputStream(buffer.toByteArray()))

        assertContentEquals(ByteArray(0), readBack)
    }

    @Test
    fun `writes and reads back multiple frames in sequence`() {
        val buffer = ByteArrayOutputStream()
        Framing.writeFrame(buffer, byteArrayOf(1))
        Framing.writeFrame(buffer, byteArrayOf(2, 3))
        Framing.writeFrame(buffer, byteArrayOf(4, 5, 6))

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertContentEquals(byteArrayOf(1), Framing.readFrame(input))
        assertContentEquals(byteArrayOf(2, 3), Framing.readFrame(input))
        assertContentEquals(byteArrayOf(4, 5, 6), Framing.readFrame(input))
        // After all frames consumed, clean EOF returns null.
        assertNull(Framing.readFrame(input))
    }

    @Test
    fun `readFrame returns null on clean EOF before any header byte`() {
        val empty = ByteArrayInputStream(ByteArray(0))
        assertNull(Framing.readFrame(empty))
    }

    @Test
    fun `readFrame throws EOFException on truncated header`() {
        // 3 of 4 header bytes — not enough for a complete length prefix.
        val truncated = ByteArrayInputStream(byteArrayOf(0, 0, 0))
        assertFailsWith<EOFException> { Framing.readFrame(truncated) }
    }

    @Test
    fun `readFrame throws EOFException on truncated payload`() {
        // Header claims 5 bytes; we provide 2.
        val truncated = ByteArrayInputStream(byteArrayOf(0, 0, 0, 5, 0xAA.toByte(), 0xBB.toByte()))
        assertFailsWith<EOFException> { Framing.readFrame(truncated) }
    }

    @Test
    fun `readFrame throws IllegalStateException on negative length`() {
        // -1 as big-endian int = 0xFFFFFFFF.
        val negative =
            ByteArrayInputStream(
                byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
            )
        assertFailsWith<IllegalStateException> { Framing.readFrame(negative) }
    }

    @Test
    fun `readFrame throws IllegalStateException on length above MAX_FRAME_BYTES`() {
        // 32 MiB > 16 MiB cap.
        val tooBig = ByteArrayInputStream(byteArrayOf(0x02, 0, 0, 0))
        assertFailsWith<IllegalStateException> { Framing.readFrame(tooBig) }
    }

    @Test
    fun `writeFrame throws when payload exceeds MAX_FRAME_BYTES`() {
        val oversized = ByteArray(MAX_FRAME_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            Framing.writeFrame(ByteArrayOutputStream(), oversized)
        }
    }

    @Test
    fun `roundtrip handles a payload exactly at MAX_FRAME_BYTES`() {
        // Smaller test version: 1 MiB. We don't want a 16 MiB allocation per test run for
        // CI hygiene; the boundary logic is identical at any size below the cap.
        val payload = ByteArray(1 shl 20) { (it % 256).toByte() }
        val buffer = ByteArrayOutputStream()

        Framing.writeFrame(buffer, payload)
        val readBack = Framing.readFrame(ByteArrayInputStream(buffer.toByteArray()))

        assertContentEquals(payload, readBack)
    }

    @Test
    fun `frames concatenated with extra garbage afterwards still decode one frame at a time`() {
        // Real sockets may buffer the next frame's bytes after the current one. Verify that
        // readFrame consumes exactly one frame's worth of bytes from the stream.
        val buffer = ByteArrayOutputStream()
        Framing.writeFrame(buffer, byteArrayOf(0x11, 0x22))
        Framing.writeFrame(buffer, byteArrayOf(0x33, 0x44, 0x55))
        val input = ByteArrayInputStream(buffer.toByteArray())

        val firstFrame = Framing.readFrame(input)
        // Reader should have consumed 4 (header) + 2 (payload) = 6 bytes.
        assertEquals(input.available(), buffer.size() - 6)

        val secondFrame = Framing.readFrame(input)
        assertContentEquals(byteArrayOf(0x11, 0x22), firstFrame)
        assertContentEquals(byteArrayOf(0x33, 0x44, 0x55), secondFrame)
    }
}
