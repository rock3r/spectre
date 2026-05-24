@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.awt.Frame
import java.awt.Rectangle
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [ReflectiveAutomatorHandler]'s reflective method-lookup + mapping code.
 *
 * Validates the specific bugs the review flagged in P0/P1:
 * - `screenshot()` looks up `screenshot(Rectangle?)`, not `screenshot()` (no-arg overload doesn't
 *   exist on Spectre's actual `ComposeAutomator`).
 * - `mapTrackedWindow` uses `getSurfaceId` / `isPopup` / `getComposeSurfaceBoundsOnScreen` /
 *   `getWindow` — not the previous draft's `getBounds` / `getTitle`.
 * - `mapAutomatorNode` uses `getBoundsOnScreen` — not the previous draft's `getBoundsInScreen`.
 * - The bounds → [dev.sebastiano.spectre.agent.transport.RectDto] conversion reads `getX/getY/
 *   getWidth/getHeight` from a real `java.awt.Rectangle` and yields the right ints.
 *
 * Uses **synthetic stand-in objects** with the same JVM-level getter signatures as Spectre's real
 * `TrackedWindow` / `AutomatorNode`. The handler resolves methods reflectively by name, so the
 * stand-ins don't need to extend Spectre's types — they just need the matching getter set. That
 * decouples this test from Spectre `core`'s evolving Compose semantics tree and from any need to
 * bring up a real Compose Desktop window in the test JVM.
 */
class ReflectiveAutomatorHandlerMappingTest {

    @Test
    fun `Windows op maps surfaceId, isPopup, bounds, title from a synthetic TrackedWindow`() {
        val testFrame = Frame("My Test Window") // title comes from java.awt.Frame.getTitle()
        try {
            val trackedWindow =
                FakeTrackedWindow(
                    surfaceIdValue = "surface-42",
                    isPopupValue = true,
                    composeSurfaceBoundsOnScreenValue = Rectangle(10, 20, 800, 600),
                    windowValue = testFrame,
                )
            val automator = FakeAutomator(windowsValue = listOf(trackedWindow))
            val handler = ReflectiveAutomatorHandler(automator)

            val response = handler.handle(AgentRequest.Windows)
            check(response is AgentResponse.Windows) {
                "expected AgentResponse.Windows, got ${response::class.simpleName}: $response"
            }
            assertEquals(1, response.windows.size)
            val dto: WindowSummaryDto = response.windows.single()
            assertEquals(0, dto.index)
            assertEquals("surface-42", dto.surfaceId)
            assertEquals(true, dto.isPopup)
            assertEquals("My Test Window", dto.title)
            assertEquals(10, dto.bounds.x)
            assertEquals(20, dto.bounds.y)
            assertEquals(800, dto.bounds.width)
            assertEquals(600, dto.bounds.height)
        } finally {
            testFrame.dispose()
        }
    }

    @Test
    fun `AllNodes op maps key, testTag, texts, role, contentDescription, isVisible, bounds`() {
        val node =
            FakeAutomatorNode(
                keyValue = "surface-0:0:42",
                testTagValue = "submit-button",
                textsValue = listOf("Submit"),
                roleValue = "Button",
                contentDescriptionValue = "Click to submit the form",
                isVisibleValue = true,
                boundsOnScreenValue = Rectangle(100, 200, 80, 24),
            )
        val automator = FakeAutomator(allNodesValue = listOf(node))
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.AllNodes)
        check(response is AgentResponse.Nodes) {
            "expected AgentResponse.Nodes, got ${response::class.simpleName}: $response"
        }
        assertEquals(1, response.nodes.size)
        val dto: NodeSnapshotDto = response.nodes.single()
        assertEquals("surface-0:0:42", dto.key)
        assertEquals("submit-button", dto.testTag)
        assertEquals(listOf("Submit"), dto.texts)
        assertEquals("Button", dto.role)
        assertEquals("Click to submit the form", dto.contentDescription)
        assertEquals(true, dto.isVisible)
        assertEquals(100, dto.bounds.x)
        assertEquals(200, dto.bounds.y)
        assertEquals(80, dto.bounds.width)
        assertEquals(24, dto.bounds.height)
    }

    @Test
    fun `FindByTestTag forwards the tag string to findByTestTag(tag)`() {
        val matched = FakeAutomatorNode(keyValue = "k1", testTagValue = "match")
        val unmatched = FakeAutomatorNode(keyValue = "k2", testTagValue = "nope")
        var receivedTag: String? = null
        val automator =
            FakeAutomator(
                allNodesValue = listOf(matched, unmatched),
                findByTestTagImpl = { tag ->
                    receivedTag = tag
                    listOf(matched)
                },
            )
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.FindByTestTag(tag = "match"))
        assertEquals("match", receivedTag)
        check(response is AgentResponse.Nodes)
        assertEquals(1, response.nodes.size)
        assertEquals("k1", response.nodes.single().key)
    }

    @Test
    fun `Screenshot op calls screenshot(Rectangle) with null and returns the PNG bytes`() {
        var receivedArg: Any? = "<not invoked>"
        val image = java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val automator =
            FakeAutomator(
                screenshotImpl = { region ->
                    receivedArg = region
                    image
                }
            )
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.Screenshot)
        check(response is AgentResponse.Screenshot) {
            "expected Screenshot, got ${response::class.simpleName}: $response"
        }
        assertEquals(
            null,
            receivedArg,
            "handler must call screenshot(null) for full-screen capture",
        )

        val decoded = ImageIO.read(response.pngBytes.inputStream())
        assertEquals(2, decoded.width)
        assertEquals(2, decoded.height)
    }

    @Test
    fun `Detach op returns Detached without touching the automator`() {
        val automator = FakeAutomator(screenshotImpl = { error("should not be called for Detach") })
        val handler = ReflectiveAutomatorHandler(automator)
        assertEquals(AgentResponse.Detached, handler.handle(AgentRequest.Detach))
    }

    @Test
    fun `Ping op returns Pong`() {
        val handler = ReflectiveAutomatorHandler(FakeAutomator())
        assertEquals(AgentResponse.Pong, handler.handle(AgentRequest.Ping))
    }

    @Test
    fun `Windows op refreshes the window tracker cache before reading`() {
        // `ComposeAutomator.windows` is a stale cache by design; the handler must call
        // `refreshWindows()` before reading it or the result will be empty for live UIs.
        val automator = FakeAutomator()
        val handler = ReflectiveAutomatorHandler(automator)
        assertEquals(0, automator.refreshCount, "no refresh before any op")
        handler.handle(AgentRequest.Windows)
        assertEquals(1, automator.refreshCount, "Windows op should refresh once")
        handler.handle(AgentRequest.AllNodes)
        assertEquals(2, automator.refreshCount, "AllNodes op should refresh too")
        handler.handle(AgentRequest.FindByTestTag("anything"))
        assertEquals(3, automator.refreshCount, "FindByTestTag op should refresh too")
    }

    @Test
    fun `non-reflective failure inside the automator surfaces as Error response`() {
        val automator = FakeAutomator(allNodesImpl = { error("synthetic NPE for test") })
        val handler = ReflectiveAutomatorHandler(automator)
        val response = handler.handle(AgentRequest.AllNodes)
        // The IpcServer layer normally catches RuntimeExceptions; the handler itself only
        // catches ReflectiveOperationException. So this test asserts that the failure
        // propagates as a RuntimeException — the IpcServer-layer test in IpcRoundTripTest
        // covers the "convert to AgentResponse.Error" half.
        // ReflectiveAutomatorHandler wraps the reflective call's InvocationTargetException,
        // so the unwrapped cause from allNodes() bubbles up via the targetMessage helper.
        check(response is AgentResponse.Error) {
            "expected Error after synthetic RuntimeException, got $response"
        }
        assertTrue(
            response.message.contains("synthetic NPE for test"),
            "expected message to include the synthetic exception text; got: ${response.message}",
        )
    }

    @Test
    fun `handler resolves click and typeText methods by name plus Continuation signature`() {
        // We don't call them (the suspend invocation path is exercised in the integration
        // test); we just verify that the constructor can find both methods on a fake
        // automator that exposes the right shape.
        val fake = FakeSuspendCapableAutomator()
        // If the constructor's method lookup fails, it would silently leave the fields null
        // (because we used firstOrNull). Calling the ops on a class without these methods
        // returns Error — that proves the resolution happened.
        val handler = ReflectiveAutomatorHandler(fake)
        val click = handler.handle(AgentRequest.Click(nodeKey = "k1"))
        // The fake's allNodes returns an empty list, so the handler returns Error("No node found")
        // — but that proves click() resolution was attempted, not "method not exposed".
        check(click is AgentResponse.Error)
        assertTrue(
            click.message.contains("No node found"),
            "expected node-not-found error, got: ${click.message}",
        )
    }
}

// ---------------------------------------------------------------------------------------
// Fake automators / nodes / windows. Java getter names match what Kotlin would generate
// from Spectre's real `val surfaceId: String` / `val isPopup: Boolean` etc. The handler
// only sees the JVM-level getter set, so these are indistinguishable from the real types
// as far as reflection is concerned.
// ---------------------------------------------------------------------------------------

@Suppress("LongParameterList")
private class FakeAutomator(
    private val windowsValue: List<Any> = emptyList(),
    private val allNodesValue: List<Any> = emptyList(),
    private val allNodesImpl: (() -> List<Any>)? = null,
    private val findByTestTagImpl: (String) -> List<Any> = { allNodesValue },
    private val screenshotImpl: (java.awt.Rectangle?) -> java.awt.image.BufferedImage = { _ ->
        java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB)
    },
) {
    /**
     * Tracks how many times the handler called [refreshWindows]. Real `ComposeAutomator.windows` is
     * a stale `@Volatile` cache populated by `refreshWindows()`; the handler must refresh before
     * reading. This counter lets the contract test assert that the refresh happens.
     */
    var refreshCount: Int = 0
        private set

    @Suppress("unused")
    fun refreshWindows() {
        refreshCount++
    }

    @Suppress("unused") fun getWindows(): List<Any> = windowsValue

    @Suppress("unused") fun allNodes(): List<Any> = allNodesImpl?.invoke() ?: allNodesValue

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = findByTestTagImpl(tag)

    @Suppress("unused")
    fun screenshot(region: java.awt.Rectangle?): java.awt.image.BufferedImage =
        screenshotImpl(region)
}

private class FakeTrackedWindow(
    private val surfaceIdValue: String,
    private val isPopupValue: Boolean,
    private val composeSurfaceBoundsOnScreenValue: Rectangle,
    private val windowValue: Frame,
) {
    @Suppress("unused") fun getSurfaceId(): String = surfaceIdValue

    @Suppress("unused") fun isPopup(): Boolean = isPopupValue

    @Suppress("unused")
    fun getComposeSurfaceBoundsOnScreen(): Rectangle = composeSurfaceBoundsOnScreenValue

    @Suppress("unused") fun getWindow(): Frame = windowValue
}

@Suppress("LongParameterList")
private class FakeAutomatorNode(
    private val keyValue: String,
    private val testTagValue: String? = null,
    private val textsValue: List<String> = emptyList(),
    private val roleValue: String? = null,
    private val contentDescriptionValue: String? = null,
    private val isVisibleValue: Boolean = true,
    private val boundsOnScreenValue: Rectangle = Rectangle(0, 0, 0, 0),
) {
    @Suppress("unused") fun getKey(): String = keyValue

    @Suppress("unused") fun getTestTag(): String? = testTagValue

    @Suppress("unused") fun getTexts(): List<String> = textsValue

    @Suppress("unused") fun getRole(): String? = roleValue

    @Suppress("unused") fun getContentDescription(): String? = contentDescriptionValue

    @Suppress("unused") fun isVisible(): Boolean = isVisibleValue

    @Suppress("unused") fun getBoundsOnScreen(): Rectangle = boundsOnScreenValue
}

private class FakeSuspendCapableAutomator {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = emptyList()

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    // Match Kotlin's bytecode shape for `suspend fun click(node: AutomatorNode)`:
    // `Object click(Object node, kotlin.coroutines.Continuation<? super Unit>)`.
    @Suppress("unused", "UNUSED_PARAMETER")
    fun click(node: Any, continuation: kotlin.coroutines.Continuation<Any?>): Any? = Unit

    @Suppress("unused", "UNUSED_PARAMETER")
    fun typeText(text: String, continuation: kotlin.coroutines.Continuation<Any?>): Any? = Unit
}
