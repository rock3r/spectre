@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoordinatorFrameCodecTest {

    private val codec = CoordinatorFrameCodec(maxPayloadBytes = 32)

    @Test
    fun `frame round-trips with the current protocol version`() {
        val payload = byteArrayOf(1, 2, 3, 4)

        val decoded = codec.decode(codec.encode(payload))

        assertEquals(INPUT_COORDINATOR_PROTOCOL_VERSION, decoded.protocolVersion)
        assertContentEquals(payload, decoded.payload)
    }

    @Test
    fun `oversized outbound and inbound frames are rejected with stable taxonomy`() {
        val outbound = assertFailsWith<CoordinatorProtocolException> { codec.encode(ByteArray(33)) }
        assertEquals(CoordinatorProtocolError.FRAME_TOO_LARGE, outbound.error)

        val inbound = ByteBuffer.allocate(Int.SIZE_BYTES).putInt(33).array() + ByteArray(33)
        val inboundFailure = assertFailsWith<CoordinatorProtocolException> { codec.decode(inbound) }
        assertEquals(CoordinatorProtocolError.FRAME_TOO_LARGE, inboundFailure.error)
    }

    @Test
    fun `truncated and length-mismatched frames are rejected as malformed`() {
        val truncated =
            assertFailsWith<CoordinatorProtocolException> { codec.decode(byteArrayOf(0)) }
        assertEquals(CoordinatorProtocolError.MALFORMED_FRAME, truncated.error)

        val mismatched =
            ByteBuffer.allocate(Int.SIZE_BYTES + Short.SIZE_BYTES + 1)
                .putInt(2)
                .putShort(INPUT_COORDINATOR_PROTOCOL_VERSION.toShort())
                .put(7)
                .array()
        val mismatchFailure =
            assertFailsWith<CoordinatorProtocolException> { codec.decode(mismatched) }
        assertEquals(CoordinatorProtocolError.MALFORMED_FRAME, mismatchFailure.error)
    }

    @Test
    fun `incompatible protocol version fails closed`() {
        val frame = codec.encode(byteArrayOf(9)).copyOf()
        ByteBuffer.wrap(frame, Int.SIZE_BYTES, Short.SIZE_BYTES).putShort(99)

        val failure = assertFailsWith<CoordinatorProtocolException> { codec.decode(frame) }

        assertEquals(CoordinatorProtocolError.INCOMPATIBLE_VERSION, failure.error)
        assertEquals(99, failure.peerVersion)
    }
}
