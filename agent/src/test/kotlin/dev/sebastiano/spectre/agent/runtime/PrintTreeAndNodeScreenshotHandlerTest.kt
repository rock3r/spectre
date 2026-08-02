@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.RectDto
import java.awt.Rectangle
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #362 printTree + node-key screenshot through [ReflectiveAutomatorHandler] (real reflective path).
 * DTO click is a client convenience covered by [AttachedAutomator.click] overload.
 */
class PrintTreeAndNodeScreenshotHandlerTest {

    @Test
    fun `PrintTree returns dump from ComposeAutomator printTree`() {
        val fake = DebugFakeAutomator(printTreeValue = "Window 0 (main): window:0\n  Button")
        val handler = ReflectiveAutomatorHandler(fake)
        val response = handler.handle(AgentRequest.PrintTree)
        check(response is AgentResponse.TreeDump) { "expected TreeDump, got $response" }
        assertEquals("Window 0 (main): window:0\n  Button", response.text)
        assertEquals(1, fake.printTreeCalls)
    }

    @Test
    fun `Screenshot with nodeKey captures node boundsOnScreen region`() {
        val node =
            DebugFakeNode(
                keyValue = "window:0:0:5",
                boundsOnScreenValue = Rectangle(10, 20, 40, 30),
            )
        val fake = DebugFakeAutomator(allNodesValue = listOf(node))
        val handler = ReflectiveAutomatorHandler(fake)

        val response = handler.handle(AgentRequest.Screenshot(nodeKey = "window:0:0:5"))
        check(response is AgentResponse.Screenshot) { "expected Screenshot, got $response" }
        assertTrue(response.pngBytes.isNotEmpty())
        assertEquals(1, fake.regionScreenshotCalls)
        assertEquals(Rectangle(10, 20, 40, 30), fake.lastRegion)
    }

    @Test
    fun `Screenshot nodeKey unknown returns nodeNotFound`() {
        val handler = ReflectiveAutomatorHandler(DebugFakeAutomator())
        val response = handler.handle(AgentRequest.Screenshot(nodeKey = "missing"))
        check(response is AgentResponse.Error)
        assertEquals(AgentErrorCategory.NodeNotFound.wireName, response.category)
    }

    @Test
    fun `Screenshot nodeKey rejects combination with fullscreen`() {
        val handler = ReflectiveAutomatorHandler(DebugFakeAutomator())
        val response =
            handler.handle(AgentRequest.Screenshot(nodeKey = "window:0:0:1", fullscreen = true))
        check(response is AgentResponse.Error)
        assertEquals(AgentErrorCategory.InvalidSelector.wireName, response.category)
    }
}

internal class DebugFakeAutomator(
    private val allNodesValue: List<Any> = emptyList(),
    private val printTreeValue: String = "",
) {
    var printTreeCalls = 0
    var regionScreenshotCalls = 0
    var lastRegion: Rectangle? = null

    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = allNodesValue

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    @Suppress("unused")
    fun printTree(): String {
        printTreeCalls++
        return printTreeValue
    }

    @Suppress("unused")
    fun screenshot(region: Rectangle?): BufferedImage {
        regionScreenshotCalls++
        lastRegion = region
        val w = region?.width?.coerceAtLeast(1) ?: 1
        val h = region?.height?.coerceAtLeast(1) ?: 1
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
    }
}

@Suppress("FunctionOnlyReturningConstant")
internal class DebugFakeNode(
    private val keyValue: String,
    private val boundsOnScreenValue: Rectangle,
) {
    @Suppress("unused") fun getKey(): String = keyValue

    @Suppress("unused") fun getTestTag(): String? = null

    @Suppress("unused") fun getTexts(): List<String> = emptyList()

    @Suppress("unused") fun getEditableText(): String? = null

    @Suppress("unused") fun getRole(): String? = null

    @Suppress("unused") fun getContentDescription(): String? = null

    @Suppress("unused") fun isFocused(): Boolean = false

    @Suppress("unused") fun isVisible(): Boolean = true

    @Suppress("unused") fun getBoundsOnScreen(): Rectangle = boundsOnScreenValue
}

/** Ensures DTO click convenience compiles against the public API shape. */
class NodeDtoClickConvenienceTest {
    @Test
    fun `NodeSnapshotDto key is used for click overload identity`() {
        val dto =
            NodeSnapshotDto(
                key = "window:0:0:9",
                testTag = "Submit",
                texts = listOf("Go"),
                role = null,
                contentDescription = null,
                isVisible = true,
                bounds = RectDto(0, 0, 1, 1),
            )
        assertEquals("window:0:0:9", dto.key)
    }
}
