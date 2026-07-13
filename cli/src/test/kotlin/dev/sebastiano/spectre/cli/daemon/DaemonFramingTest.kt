package dev.sebastiano.spectre.cli.daemon

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class DaemonFramingTest {
    @Test
    fun `writeFrame prefixes payload with four byte big endian length`() {
        val output = ByteArrayOutputStream()

        DaemonFraming.writeFrame(output, byteArrayOf(0x41, 0x42, 0x43))

        assertContentEquals(byteArrayOf(0, 0, 0, 3, 0x41, 0x42, 0x43), output.toByteArray())
    }

    @Test
    fun `readFrame returns payload and clean eof returns null`() {
        val input = ByteArrayInputStream(byteArrayOf(0, 0, 0, 2, 0x7a, 0x7b))

        assertContentEquals(byteArrayOf(0x7a, 0x7b), DaemonFraming.readFrame(input))
        assertNull(DaemonFraming.readFrame(input))
    }

    @Test
    fun `readFrame rejects truncated header truncated payload and invalid length`() {
        assertFailsWith<EOFException> {
            DaemonFraming.readFrame(ByteArrayInputStream(byteArrayOf(0, 0)))
        }
        assertFailsWith<EOFException> {
            DaemonFraming.readFrame(ByteArrayInputStream(byteArrayOf(0, 0, 0, 4, 1, 2)))
        }
        assertFailsWith<IllegalStateException> {
            DaemonFraming.readFrame(ByteArrayInputStream(byteArrayOf(-1, -1, -1, -1)))
        }
    }

    @Test
    fun `writeFrame rejects payloads above daemon frame cap`() {
        val tooLarge = ByteArray(DaemonFraming.MaxFrameBytes + 1)

        val error =
            assertFailsWith<IllegalArgumentException> {
                DaemonFraming.writeFrame(ByteArrayOutputStream(), tooLarge)
            }

        assertEquals(
            "Frame payload size ${tooLarge.size} exceeds MAX_FRAME_BYTES=${DaemonFraming.MaxFrameBytes}",
            error.message,
        )
    }
}
