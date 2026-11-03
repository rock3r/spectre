@file:OptIn(
    dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class,
    kotlinx.serialization.ExperimentalSerializationApi::class,
)

package dev.sebastiano.spectre.agent.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/**
 * Wire compatibility for the node-state fields added to [NodeSnapshotDto] in #576: clickability,
 * toggleable state, progress range, tree links, and window-relative bounds.
 *
 * The fields are defaulted, so a peer built before #576 must still decode a newer payload (it drops
 * the unknown keys) and a newer peer must decode an older payload (it fills in the defaults).
 */
class NodeSnapshotWireCompatTest {

    private val cbor = Cbor { ignoreUnknownKeys = true }

    @Test
    fun `node state fields round-trip through the wire codec`() {
        val response =
            AgentResponse.Nodes(
                listOf(
                    NodeSnapshotDto(
                        key = "surface-0:0:3",
                        testTag = "volume",
                        texts = emptyList(),
                        role = "Checkbox",
                        contentDescription = null,
                        isVisible = true,
                        bounds = RectDto(10, 20, 100, 40),
                        isClickable = true,
                        toggleableState = "Indeterminate",
                        progress = ProgressRangeDto(current = 0.25f, min = 0f, max = 1f, steps = 3),
                        parentKey = "surface-0:0:1",
                        childKeys = listOf("surface-0:0:4", "surface-0:0:5"),
                        boundsInWindow = RectDto(20, 40, 200, 80),
                    )
                )
            )

        assertEquals(response, WireCodec.decodeResponse(WireCodec.encode(response)))
    }

    @Test
    fun `node state fields default when an older peer leaves them out`() {
        val legacyBytes =
            cbor.encodeToByteArray(
                LegacyNodeSnapshotDto.serializer(),
                LegacyNodeSnapshotDto(
                    key = "surface-0:0:1",
                    testTag = "submit",
                    texts = listOf("Submit"),
                    role = "Button",
                    contentDescription = null,
                    isVisible = true,
                    bounds = RectDto(10, 20, 100, 40),
                ),
            )

        val decoded = cbor.decodeFromByteArray(NodeSnapshotDto.serializer(), legacyBytes)

        assertFalse(decoded.isClickable)
        assertNull(decoded.toggleableState)
        assertNull(decoded.progress)
        assertNull(decoded.parentKey)
        assertTrue(decoded.childKeys.isEmpty())
        assertNull(decoded.boundsInWindow)
        assertEquals("surface-0:0:1", decoded.key)
    }

    @Test
    fun `an older peer still decodes a payload that carries node state fields`() {
        val node =
            NodeSnapshotDto(
                key = "surface-0:0:3",
                testTag = "volume",
                texts = emptyList(),
                role = "Checkbox",
                contentDescription = "Mute",
                isVisible = true,
                bounds = RectDto(10, 20, 100, 40),
                isClickable = true,
                toggleableState = "On",
                progress = ProgressRangeDto(current = 0.5f, min = 0f, max = 1f),
                parentKey = "surface-0:0:1",
                childKeys = listOf("surface-0:0:4"),
                boundsInWindow = RectDto(20, 40, 200, 80),
            )

        val legacy =
            cbor.decodeFromByteArray(
                LegacyNodeSnapshotDto.serializer(),
                cbor.encodeToByteArray(NodeSnapshotDto.serializer(), node),
            )

        assertEquals("surface-0:0:3", legacy.key)
        assertEquals("Mute", legacy.contentDescription)
        assertEquals(RectDto(10, 20, 100, 40), legacy.bounds)
    }

    /** [NodeSnapshotDto] as it was on the wire before #576. */
    @Serializable
    private data class LegacyNodeSnapshotDto(
        val key: String,
        val testTag: String?,
        val texts: List<String>,
        val editableText: String? = null,
        val role: String?,
        val contentDescription: String?,
        val contentDescriptions: List<String> = emptyList(),
        val isFocused: Boolean = false,
        val isDisabled: Boolean = false,
        val isSelected: Boolean = false,
        val isVisible: Boolean,
        val bounds: RectDto,
    )
}
