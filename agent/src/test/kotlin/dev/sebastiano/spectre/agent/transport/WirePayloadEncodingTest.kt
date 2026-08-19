@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Bulk payloads must reach the wire as CBOR byte strings, not arrays of integers.
 *
 * Without `@ByteString`, kotlinx serializes each signed byte as its own CBOR integer and the
 * encoded response runs about 1.8x the payload. Every budget in the transport is expressed against
 * payload size — the still-fallback threshold, the atomic-capture guard, and the "64MiB holds a
 * worst-case 4K desktop" sizing — so that expansion silently invalidates all of them: a still that
 * passes its check still frames past the budget, and the documented fallback never runs.
 */
class WirePayloadEncodingTest {

    @Test
    fun `a screenshot response does not balloon on the wire`() {
        // Incompressible payload: CBOR does not compress, so any growth here is pure encoding.
        val png = Random(1).nextBytes(1 shl 20)

        val encoded = WireCodec.encode(OpResponse(opId = 1L, body = AgentResponse.Screenshot(png)))

        assertTrue(
            encoded.size < png.size + ENVELOPE_SLACK_BYTES,
            "encoded ${encoded.size} for a ${png.size} payload — byte fields must be CBOR byte " +
                "strings, or every payload budget in this transport is wrong",
        )
    }

    @Test
    fun `an atomic capture response does not balloon on the wire`() {
        val png = Random(2).nextBytes(1 shl 20)
        val json = Random(3).nextBytes(1 shl 16)

        val encoded =
            WireCodec.encode(
                OpResponse(
                    opId = 1L,
                    body =
                        AgentResponse.Capture(
                            windowIndex = 0,
                            schemaVersion = 1,
                            captureJsonUtf8 = json,
                            pngBytes = png,
                            nodeCount = 1,
                            taggedNodeCount = 1,
                            textedNodeCount = 1,
                            imageWidth = 10,
                            imageHeight = 10,
                            captureDurationMs = 1L,
                        ),
                )
            )

        assertTrue(
            encoded.size < png.size + json.size + ENVELOPE_SLACK_BYTES,
            "encoded ${encoded.size} for ${png.size + json.size} of payload",
        )
    }

    @Test
    fun `byte payloads survive the round trip unchanged`() {
        val png = Random(4).nextBytes(4096)

        val decoded =
            WireCodec.decodeOpResponse(
                WireCodec.encode(OpResponse(7L, AgentResponse.Screenshot(png)))
            )

        val body = assertIs<AgentResponse.Screenshot>(decoded.body)
        assertContentEquals(png, body.pngBytes)
    }

    private companion object {
        /**
         * Frame headers, op id, and the discriminator — kilobytes, not a multiple of the payload.
         */
        const val ENVELOPE_SLACK_BYTES: Int = 4096
    }
}
