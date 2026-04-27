package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.core.NodeKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Cross-boundary contract test for [NodeKey].
 *
 * The NodeKey string form is the wire identity that crosses every Spectre boundary that needs to
 * reference a node — between the in-process API and downstream test code, between the future HTTP
 * transport (#9) and any client, and between recordings/screenshots and the nodes they were
 * captured against. This test pins the round-trip contract so any implementation change has to be
 * deliberate.
 */
class NodeKeyContractTest {

    @Test
    fun `toString uses surface colon owner colon node form`() {
        val key = NodeKey(surfaceId = "main", ownerIndex = 0, nodeId = 42)
        assertEquals("main:0:42", key.toString())
    }

    @Test
    fun `parse round-trips a simple key`() {
        val key = NodeKey(surfaceId = "popup", ownerIndex = 1, nodeId = 7)
        assertEquals(key, NodeKey.parse(key.toString()))
    }

    @Test
    fun `parse preserves colons embedded in the surface id`() {
        // Surface IDs come from the host application and may contain colons (window titles,
        // composite ids, etc.). Parsing splits from the right so the surface portion stays
        // intact regardless of how many colons it contains.
        val key = NodeKey(surfaceId = "tool:window:1", ownerIndex = 2, nodeId = 99)
        val round = NodeKey.parse(key.toString())
        assertEquals(key, round)
        assertEquals("tool:window:1", round.surfaceId)
        assertEquals(2, round.ownerIndex)
        assertEquals(99, round.nodeId)
    }

    @Test
    fun `parse rejects malformed strings`() {
        assertFailsWith<IllegalArgumentException> { NodeKey.parse("only-surface") }
        assertFailsWith<IllegalArgumentException> { NodeKey.parse("missing-node:0") }
    }
}
