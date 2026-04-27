package dev.sebastiano.spectre.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NodeKeyTest {

    @Test
    fun `toString formats as surfaceId colon ownerIndex colon nodeId`() {
        val key = NodeKey(surfaceId = "win1", ownerIndex = 0, nodeId = 42)
        assertEquals("win1:0:42", key.toString())
    }

    @Test
    fun `parse roundtrips with toString`() {
        val original = NodeKey(surfaceId = "popup:3", ownerIndex = 1, nodeId = 7)
        val parsed = NodeKey.parse(original.toString())
        assertEquals(original, parsed)
    }

    @Test
    fun `parse handles surfaceId containing colons`() {
        // surfaceId="popup:3", ownerIndex=1, nodeId=7
        // toString = "popup:3:1:7"
        // parse splits on last two colons
        val parsed = NodeKey.parse("popup:3:1:7")
        assertEquals(NodeKey(surfaceId = "popup:3", ownerIndex = 1, nodeId = 7), parsed)
    }

    @Test
    fun `equality is structural`() {
        val a = NodeKey(surfaceId = "main", ownerIndex = 0, nodeId = 5)
        val b = NodeKey(surfaceId = "main", ownerIndex = 0, nodeId = 5)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `parse throws on invalid input`() {
        assertFailsWith<IllegalArgumentException> { NodeKey.parse("invalid") }
    }

    @Test
    fun `parse throws on single colon`() {
        assertFailsWith<IllegalArgumentException> { NodeKey.parse("a:b") }
    }

    @Test
    fun `parse roundtrips with empty surfaceId`() {
        val original = NodeKey(surfaceId = "", ownerIndex = 0, nodeId = 1)
        val parsed = NodeKey.parse(original.toString())
        assertEquals(original, parsed)
    }

    @Test
    fun `owner index participates in node identity`() {
        val ownerZero = NodeKey(surfaceId = "window:0", ownerIndex = 0, nodeId = 42)
        val ownerOne = NodeKey(surfaceId = "window:0", ownerIndex = 1, nodeId = 42)

        kotlin.test.assertNotEquals(ownerZero, ownerOne)
    }
}
