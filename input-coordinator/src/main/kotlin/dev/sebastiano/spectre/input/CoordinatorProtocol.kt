@file:OptIn(ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input

import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Request and response operations carried by the local coordinator protocol. */
@Serializable
@ExperimentalSpectreInputCoordinationApi
public enum class CoordinatorWireKind {
    HEALTH,
    SESSION_OPEN,
    ACQUIRE,
    CANCEL,
    RELEASE,
    HEARTBEAT,
    STATUS,
    REVOKE,
    RESPONSE,
}

/** Redacted holder status carried across the local control protocol. */
@Serializable
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorWireHolder(
    public val leaseId: String,
    public val clientId: String,
    public val processId: Long,
    public val ownerLabel: String? = null,
    public val state: String,
    public val fence: Long,
    public val acquisitionAgeMillis: Long,
    public val heartbeatAgeMillis: Long,
    public val currentOperation: String? = null,
)

/** Redacted queued-owner status carried across the local control protocol. */
@Serializable
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorWireWaiter(
    public val requestId: String,
    public val clientId: String,
    public val processId: Long,
    public val ownerLabel: String? = null,
    public val position: Int,
)

/** Recovery quarantine details needed for an exact-ID unsafe recovery decision. */
@Serializable
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorWireQuarantine(
    public val predecessorLeaseId: String,
    public val predecessorEpoch: String,
    public val clientId: String,
    public val processId: Long,
    public val ownerLabel: String? = null,
    public val releaseEligibleAtMillis: Long,
)

/** Machine-readable status payload for one desktop resource. */
@Serializable
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorWireStatus(
    public val resourceKey: String,
    public val holder: CoordinatorWireHolder? = null,
    public val waiters: List<CoordinatorWireWaiter> = emptyList(),
    public val quarantine: CoordinatorWireQuarantine? = null,
)

/** Versioned request/response envelope for the trusted-local coordinator boundary. */
@Serializable
@ExperimentalSpectreInputCoordinationApi
public data class CoordinatorWireMessage(
    public val kind: CoordinatorWireKind,
    public val requestId: String? = null,
    public val clientId: String? = null,
    public val resourceKey: String? = null,
    public val processId: Long? = null,
    public val ownerLabel: String? = null,
    public val timeoutMillis: Long? = null,
    public val waitForLease: Boolean = true,
    public val currentOperation: String? = null,
    public val coordinatorEpoch: String? = null,
    public val leaseId: String? = null,
    public val fence: Long? = null,
    public val requesterLabel: String? = null,
    public val reason: String? = null,
    public val force: Boolean = false,
    public val ok: Boolean = true,
    public val errorCode: String? = null,
    public val message: String? = null,
    public val queuePosition: Int? = null,
    public val unsafeTakeover: Boolean = false,
    public val status: CoordinatorWireStatus? = null,
)

/** Bounded JSON payload codec layered over [CoordinatorFrameCodec]. */
@ExperimentalSpectreInputCoordinationApi
public class CoordinatorWireCodec(
    maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val json: Json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = false
    },
) {
    private val frameCodec = CoordinatorFrameCodec(maxPayloadBytes)

    /** Encodes one local request or response. */
    public fun encode(message: CoordinatorWireMessage): ByteArray =
        frameCodec.encode(json.encodeToString(message).encodeToByteArray())

    /** Decodes one complete local request or response. */
    public fun decode(frame: ByteArray): CoordinatorWireMessage =
        json.decodeFromString(frameCodec.decode(frame).payload.decodeToString())

    /** Writes one complete frame to [channel]. */
    public fun write(channel: SocketChannel, message: CoordinatorWireMessage) {
        val buffer = ByteBuffer.wrap(encode(message))
        while (buffer.hasRemaining()) channel.write(buffer)
    }

    /** Reads one frame, returning null only for clean EOF before any header byte. */
    public fun readOrNull(channel: SocketChannel): CoordinatorWireMessage? {
        val header = ByteBuffer.allocate(HEADER_BYTES)
        if (!readFully(channel, header, allowInitialEof = true)) return null
        header.flip()
        val payloadSize = header.int
        if (payloadSize < 0 || payloadSize > frameCodec.maxPayloadBytes) {
            throw CoordinatorProtocolException(
                error =
                    if (payloadSize > frameCodec.maxPayloadBytes) {
                        CoordinatorProtocolError.FRAME_TOO_LARGE
                    } else {
                        CoordinatorProtocolError.MALFORMED_FRAME
                    },
                message = "Invalid coordinator payload length $payloadSize",
            )
        }
        val payload = ByteBuffer.allocate(payloadSize)
        readFully(channel, payload, allowInitialEof = false)
        return decode(header.array() + payload.array())
    }

    /** Reads one required frame. */
    public fun read(channel: SocketChannel): CoordinatorWireMessage =
        readOrNull(channel) ?: throw EOFException("Coordinator closed before sending a frame")

    private fun readFully(
        channel: SocketChannel,
        buffer: ByteBuffer,
        allowInitialEof: Boolean,
    ): Boolean {
        var readAny = false
        while (buffer.hasRemaining()) {
            val count = channel.read(buffer)
            if (count < 0) {
                if (!readAny && allowInitialEof) return false
                throw EOFException("Coordinator closed in the middle of a frame")
            }
            readAny = readAny || count > 0
        }
        return true
    }

    public companion object {
        public const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 64 * 1024
        private const val HEADER_BYTES: Int = Int.SIZE_BYTES + Short.SIZE_BYTES
    }
}
