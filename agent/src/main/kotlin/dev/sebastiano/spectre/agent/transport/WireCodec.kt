package dev.sebastiano.spectre.agent.transport

import kotlinx.serialization.ExperimentalSerializationApi
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
}
