package dev.sebastiano.spectre.agent.transport

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor

/**
 * CBOR codec for [AgentRequest] / [AgentResponse] envelopes.
 *
 * Single shared [Cbor] instance — CBOR formats in kotlinx-serialization are thread-safe per the
 * library docs.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object WireCodec {
    private val cbor = Cbor { ignoreUnknownKeys = true }

    /** Encode a request to its CBOR bytes. */
    fun encode(request: AgentRequest): ByteArray =
        cbor.encodeToByteArray(AgentRequest.serializer(), request)

    /** Encode a response to its CBOR bytes. */
    fun encode(response: AgentResponse): ByteArray =
        cbor.encodeToByteArray(AgentResponse.serializer(), response)

    /** Decode CBOR bytes into a request. */
    fun decodeRequest(bytes: ByteArray): AgentRequest =
        cbor.decodeFromByteArray(AgentRequest.serializer(), bytes)

    /** Decode CBOR bytes into a response. */
    fun decodeResponse(bytes: ByteArray): AgentResponse =
        cbor.decodeFromByteArray(AgentResponse.serializer(), bytes)

    fun encode(request: OpRequest): ByteArray =
        cbor.encodeToByteArray(OpRequest.serializer(), request)

    fun encode(response: OpResponse): ByteArray =
        cbor.encodeToByteArray(OpResponse.serializer(), response)

    fun decodeOpRequest(bytes: ByteArray): OpRequest =
        cbor.decodeFromByteArray(OpRequest.serializer(), bytes)

    fun decodeOpResponse(bytes: ByteArray): OpResponse =
        cbor.decodeFromByteArray(OpResponse.serializer(), bytes)

    /**
     * True when [ex] looks like an unknown sealed-class discriminator (newer op against an older
     * runtime), as opposed to truncated/corrupt CBOR. Used to map decode failures to
     * [AgentErrorCategory.UnsupportedOperation] instead of hanging or treating them as internal
     * errors (#199).
     */
    fun isUnknownDiscriminator(ex: SerializationException): Boolean {
        val msg = ex.message.orEmpty().lowercase()
        return "polymorphic" in msg ||
            "serializer" in msg && ("not found" in msg || "for class" in msg || "unknown" in msg) ||
            "unknown" in msg && ("class" in msg || "type" in msg || "serial" in msg) ||
            "cannot find" in msg
    }
}
