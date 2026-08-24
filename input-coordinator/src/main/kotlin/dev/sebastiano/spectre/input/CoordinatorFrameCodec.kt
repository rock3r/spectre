@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.nio.ByteBuffer

/** Current major version of the local coordinator framing protocol. */
@ExperimentalSpectreInputCoordinationApi
public const val INPUT_COORDINATOR_PROTOCOL_VERSION: Int = 1

/** Stable failures produced before a protocol payload is decoded. */
@ExperimentalSpectreInputCoordinationApi
public enum class CoordinatorProtocolError {
    FRAME_TOO_LARGE,
    MALFORMED_FRAME,
    INCOMPATIBLE_VERSION,
}

/** A validated protocol payload and the peer version that framed it. */
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorFrame(public val protocolVersion: Int, public val payload: ByteArray) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is CoordinatorFrame &&
                protocolVersion == other.protocolVersion &&
                payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * protocolVersion + payload.contentHashCode()
}

/** A framing failure that can be mapped to a stable local-protocol response. */
@ExperimentalSpectreInputCoordinationApi
public class CoordinatorProtocolException(
    public val error: CoordinatorProtocolError,
    public val peerVersion: Int? = null,
    message: String,
) : IllegalArgumentException(message)

/** Length-prefix codec that rejects unbounded, truncated, and incompatible local frames. */
@ExperimentalSpectreInputCoordinationApi
public class CoordinatorFrameCodec(public val maxPayloadBytes: Int) {
    init {
        require(maxPayloadBytes >= 0) { "Maximum payload size must not be negative" }
    }

    /** Encodes one payload with a bounded length prefix and protocol version. */
    public fun encode(payload: ByteArray): ByteArray {
        rejectOversized(payload.size)
        return ByteBuffer.allocate(HEADER_BYTES + payload.size)
            .putInt(payload.size)
            .putShort(INPUT_COORDINATOR_PROTOCOL_VERSION.toShort())
            .put(payload)
            .array()
    }

    /** Validates and decodes exactly one complete frame. */
    public fun decode(frame: ByteArray): CoordinatorFrame {
        if (frame.size < HEADER_BYTES) {
            malformed("Frame is shorter than its $HEADER_BYTES-byte header")
        }
        val buffer = ByteBuffer.wrap(frame)
        val payloadSize = buffer.int
        if (payloadSize < 0) {
            malformed("Frame declares a negative payload length")
        }
        rejectOversized(payloadSize)
        val protocolVersion = buffer.short.toInt() and UNSIGNED_SHORT_MASK
        if (frame.size != HEADER_BYTES + payloadSize) {
            malformed(
                "Frame length ${frame.size} does not match declared payload length $payloadSize"
            )
        }
        if (protocolVersion != INPUT_COORDINATOR_PROTOCOL_VERSION) {
            throw CoordinatorProtocolException(
                error = CoordinatorProtocolError.INCOMPATIBLE_VERSION,
                peerVersion = protocolVersion,
                message =
                    "Input coordinator protocol $protocolVersion is incompatible with " +
                        "supported version $INPUT_COORDINATOR_PROTOCOL_VERSION",
            )
        }
        val payload = ByteArray(payloadSize)
        buffer.get(payload)
        return CoordinatorFrame(protocolVersion = protocolVersion, payload = payload)
    }

    private fun rejectOversized(payloadSize: Int) {
        if (payloadSize > maxPayloadBytes) {
            throw CoordinatorProtocolException(
                error = CoordinatorProtocolError.FRAME_TOO_LARGE,
                message =
                    "Input coordinator payload is $payloadSize bytes; maximum is $maxPayloadBytes",
            )
        }
    }

    private fun malformed(message: String): Nothing =
        throw CoordinatorProtocolException(
            error = CoordinatorProtocolError.MALFORMED_FRAME,
            message = message,
        )

    private companion object {
        const val HEADER_BYTES: Int = Int.SIZE_BYTES + Short.SIZE_BYTES
        const val UNSIGNED_SHORT_MASK: Int = 0xffff
    }
}
