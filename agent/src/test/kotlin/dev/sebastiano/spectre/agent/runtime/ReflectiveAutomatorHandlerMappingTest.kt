@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import dev.sebastiano.spectre.agent.transport.WindowIdentityDto
import dev.sebastiano.spectre.agent.transport.WindowSummaryDto
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse

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
    fun `Windows op maps surfaceId, isPopup, bounds for a TrackedWindow without a Frame window`() {
        // This headless-safe variant doesn't need a real `java.awt.Frame` — the handler's
        // title-extraction path falls through to `null` when `getWindow()` doesn't return a
        // `Frame`. The non-null Frame title case is exercised by the separate
        // `…extracts title from a real java.awt.Frame` test, which `assumeFalse`s on
        // headless JVMs. Splitting keeps CI green on the Linux GitHub runner.
        val trackedWindow =
            FakeTrackedWindow(
                surfaceIdValue = "surface-42",
                isPopupValue = true,
                composeSurfaceBoundsOnScreenValue = Rectangle(10, 20, 800, 600),
                windowValue = null,
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
        assertNull(dto.title, "title should be null when getWindow() returns no Frame")
        assertEquals(10, dto.bounds.x)
        assertEquals(20, dto.bounds.y)
        assertEquals(800, dto.bounds.width)
        assertEquals(600, dto.bounds.height)
    }

    @Test
    fun `Windows op extracts title from a real java_awt_Frame`() {
        // `java.awt.Frame` instantiation requires AWT toolkit init, which throws
        // `HeadlessException` on headless JVMs (the Linux CI runner). Gate accordingly.
        assumeFalse(
            GraphicsEnvironment.isHeadless(),
            "Frame() requires a non-headless JVM; skipped on Linux CI.",
        )
        val testFrame = Frame("My Test Window") // title comes from java.awt.Frame.getTitle()
        try {
            val trackedWindow =
                FakeTrackedWindow(
                    surfaceIdValue = "surface-with-frame",
                    isPopupValue = false,
                    composeSurfaceBoundsOnScreenValue = Rectangle(0, 0, 1, 1),
                    windowValue = testFrame,
                )
            val automator = FakeAutomator(windowsValue = listOf(trackedWindow))
            val handler = ReflectiveAutomatorHandler(automator)

            val response = handler.handle(AgentRequest.Windows)
            check(response is AgentResponse.Windows)
            assertEquals("My Test Window", response.windows.single().title)
        } finally {
            testFrame.dispose()
        }
    }

    @Test
    fun `WindowIdentity op maps snapshot fields including cropRequired and null handle`() {
        val snapshot =
            FakeWindowIdentitySnapshot(
                indexValue = 0,
                surfaceIdValue = "surface-42",
                titleValue = "Main",
                isPopupValue = false,
                nativeHandleValue = null,
                cropRequiredValue = true,
                windowBoundsOnScreenValue = Rectangle(10, 20, 800, 600),
                surfaceBoundsOnScreenValue = Rectangle(18, 48, 784, 552),
                surfaceBoundsInWindowValue = Rectangle(8, 28, 784, 552),
                scaleXValue = 2.0,
                scaleYValue = 2.0,
            )
        val automator = FakeAutomator(windowIdentitiesValue = listOf(snapshot))
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.WindowIdentity(windowIndex = null))
        check(response is AgentResponse.WindowIdentities) {
            "expected WindowIdentities, got ${response::class.simpleName}: $response"
        }
        assertEquals(1, response.windows.size)
        val dto: WindowIdentityDto = response.windows.single()
        assertEquals(0, dto.index)
        assertEquals("surface-42", dto.surfaceId)
        assertEquals("Main", dto.title)
        assertEquals(false, dto.isPopup)
        assertNull(dto.nativeHandle)
        assertEquals(true, dto.cropRequired)
        assertEquals(10, dto.windowBoundsOnScreen.x)
        assertEquals(20, dto.windowBoundsOnScreen.y)
        assertEquals(800, dto.windowBoundsOnScreen.width)
        assertEquals(600, dto.windowBoundsOnScreen.height)
        assertEquals(18, dto.surfaceBoundsOnScreen.x)
        assertEquals(8, dto.surfaceBoundsInWindow.x)
        assertEquals(28, dto.surfaceBoundsInWindow.y)
        assertEquals(2.0, dto.scaleX)
        assertEquals(2.0, dto.scaleY)
    }

    @Test
    fun `WindowIdentity op with index selects a single snapshot`() {
        val snapshots =
            listOf(
                FakeWindowIdentitySnapshot(indexValue = 0, surfaceIdValue = "a"),
                FakeWindowIdentitySnapshot(indexValue = 1, surfaceIdValue = "b"),
            )
        val automator = FakeAutomator(windowIdentitiesValue = snapshots)
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.WindowIdentity(windowIndex = 1))
        check(response is AgentResponse.WindowIdentities)
        assertEquals(listOf("b"), response.windows.map { it.surfaceId })
    }

    @Test
    fun `AllNodes op maps key, testTag, texts, role, contentDescription, focused, visible, bounds`() {
        val node =
            FakeAutomatorNode(
                keyValue = "surface-0:0:42",
                testTagValue = "submit-button",
                textsValue = listOf("Submit"),
                editableTextValue = "typed text",
                roleValue = "Button",
                contentDescriptionValue = "Click to submit the form",
                isFocusedValue = true,
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
        assertEquals("typed text", dto.editableText)
        assertEquals("Button", dto.role)
        assertEquals("Click to submit the form", dto.contentDescription)
        assertEquals(true, dto.isFocused)
        assertEquals(true, dto.isVisible)
        assertEquals(100, dto.bounds.x)
        assertEquals(200, dto.bounds.y)
        assertEquals(80, dto.bounds.width)
        assertEquals(24, dto.bounds.height)
    }

    @Test
    fun `AllNodes op fails loudly when AutomatorNode visible accessor is missing`() {
        val automator = FakeAutomator(allNodesValue = listOf(FakeNodeWithoutVisible()))
        val handler = ReflectiveAutomatorHandler(automator)

        val error = assertFailsWith<IllegalStateException> { handler.handle(AgentRequest.AllNodes) }

        assertTrue(
            error.message.orEmpty().contains("does not expose isVisible()"),
            "expected API mismatch error for missing isVisible(); got: ${error.message}",
        )
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
    fun `Capture op calls capture(int) and maps AtomicCapture fields`() {
        var receivedWindowIndex: Int? = null
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val automator =
            FakeAutomator(
                captureImpl = { windowIndex ->
                    receivedWindowIndex = windowIndex
                    FakeAtomicCapture(
                        document =
                            FakeCaptureDocument(
                                schemaVersion = 1,
                                summary =
                                    FakeCaptureSummary(
                                        nodeCount = 3,
                                        taggedNodeCount = 2,
                                        textedNodeCount = 1,
                                        imageWidth = 10,
                                        imageHeight = 20,
                                        captureDurationMs = 7,
                                    ),
                            ),
                        pngBytes = png,
                    )
                }
            )
        val handler = ReflectiveAutomatorHandler(automator)

        val response = handler.handle(AgentRequest.Capture(windowIndex = 2))
        check(response is AgentResponse.Capture) {
            "expected Capture, got ${response::class.simpleName}: $response"
        }
        assertEquals(2, receivedWindowIndex)
        assertEquals(2, response.windowIndex)
        assertEquals(1, response.schemaVersion)
        assertEquals(3, response.nodeCount)
        assertEquals(2, response.taggedNodeCount)
        assertEquals(1, response.textedNodeCount)
        assertEquals(10, response.imageWidth)
        assertEquals(20, response.imageHeight)
        assertEquals(7, response.captureDurationMs)
        assertTrue(response.pngBytes.contentEquals(png))
        assertTrue(response.captureJsonUtf8.isNotEmpty())
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
    fun `targetMessage formatting uses the unwrapped cause's class and a placeholder when cause has no message`() {
        // Regression for Bugbot finding on commit 8cb205c: `targetMessage` previously fell back
        // to `javaClass.simpleName` on the *receiver* (`this` = ReflectiveOperationException —
        // typically `InvocationTargetException`) instead of `cause.javaClass.simpleName` or a
        // placeholder. With a null-message cause, the resulting Error would read
        // `"NullPointerException: InvocationTargetException"` — misleading and useless.
        // Today the helper formats as `"<causeClassName>: <causeMessage or "<no message>">"`
        // and never mentions the wrapper class.
        val automator =
            FakeAutomator(
                allNodesImpl = {
                    // No message → exercises the placeholder branch.
                    throw IllegalStateException()
                }
            )
        val handler = ReflectiveAutomatorHandler(automator)
        val response = handler.handle(AgentRequest.AllNodes)
        check(response is AgentResponse.Error) { "expected Error response, got $response" }
        assertTrue(
            response.message.startsWith("Reflective call failed: IllegalStateException:"),
            "expected error to name the unwrapped cause's class; got: ${response.message}",
        )
        assertTrue(
            response.message.contains("<no message>"),
            "expected null-message fallback placeholder; got: ${response.message}",
        )
        assertTrue(
            !response.message.contains("InvocationTargetException"),
            "the InvocationTargetException wrapper must NOT appear in user-facing errors; " +
                "got: ${response.message}",
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

    @Test
    fun `TypeText op refuses to type when no target node is focused`() {
        val fake =
            FakeSuspendCapableAutomator(
                allNodesValue = listOf(FakeAutomatorNode(keyValue = "k1", isFocusedValue = false))
            )
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { true })

        val response = handler.handle(AgentRequest.TypeText("x"))

        check(response is AgentResponse.Error)
        assertTrue(
            response.message.contains("no focused", ignoreCase = true),
            "expected focused-node guard error, got: ${response.message}",
        )
        assertEquals(emptyList(), fake.typedTexts)
    }

    @Test
    fun `TypeText op refuses to type when the target JVM does not own OS focus`() {
        val fake =
            FakeSuspendCapableAutomator(
                allNodesValue = listOf(FakeAutomatorNode(keyValue = "k1", isFocusedValue = true))
            )
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { false })

        val response = handler.handle(AgentRequest.TypeText("x"))

        check(response is AgentResponse.Error)
        assertTrue(
            response.message.contains("OS keyboard focus"),
            "expected OS-focus guard error, got: ${response.message}",
        )
        assertEquals(emptyList(), fake.typedTexts)
    }

    @Test
    fun `TypeText op dispatches only when a target node is focused`() {
        val fake =
            FakeSuspendCapableAutomator(
                allNodesValue = listOf(FakeAutomatorNode(keyValue = "k1", isFocusedValue = true))
            )
        val handler = ReflectiveAutomatorHandler(fake, isTargetJvmFocused = { true })

        val response = handler.handle(AgentRequest.TypeText("x"))

        assertEquals(AgentResponse.Ok, response)
        assertEquals(listOf("x"), fake.typedTexts)
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
    private val captureImpl: (Int) -> Any = { error("capture not stubbed") },
    private val windowIdentitiesValue: List<Any> = emptyList(),
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

    @Suppress("unused") fun capture(windowIndex: Int): Any = captureImpl(windowIndex)

    @Suppress("unused") fun windowIdentities(): List<Any> = windowIdentitiesValue
}

@Suppress("LongParameterList")
private class FakeWindowIdentitySnapshot(
    private val indexValue: Int = 0,
    private val surfaceIdValue: String = "surface",
    private val titleValue: String? = null,
    private val isPopupValue: Boolean = false,
    private val nativeHandleValue: Long? = null,
    private val cropRequiredValue: Boolean = false,
    private val windowBoundsOnScreenValue: Rectangle = Rectangle(0, 0, 100, 100),
    private val surfaceBoundsOnScreenValue: Rectangle = Rectangle(0, 0, 100, 100),
    private val surfaceBoundsInWindowValue: Rectangle = Rectangle(0, 0, 100, 100),
    private val scaleXValue: Double = 1.0,
    private val scaleYValue: Double = 1.0,
) {
    @Suppress("unused") fun getIndex(): Int = indexValue

    @Suppress("unused") fun getSurfaceId(): String = surfaceIdValue

    @Suppress("unused") fun getTitle(): String? = titleValue

    @Suppress("unused") fun isPopup(): Boolean = isPopupValue

    @Suppress("unused") fun getNativeHandle(): Long? = nativeHandleValue

    @Suppress("unused") fun getCropRequired(): Boolean = cropRequiredValue

    @Suppress("unused") fun getWindowBoundsOnScreen(): Rectangle = windowBoundsOnScreenValue

    @Suppress("unused") fun getSurfaceBoundsOnScreen(): Rectangle = surfaceBoundsOnScreenValue

    @Suppress("unused") fun getSurfaceBoundsInWindow(): Rectangle = surfaceBoundsInWindowValue

    @Suppress("unused") fun getScaleX(): Double = scaleXValue

    @Suppress("unused") fun getScaleY(): Double = scaleYValue
}

/** Mirrors `AtomicCapture` getters the reflective capture mapper reads. */
private class FakeAtomicCapture(
    private val document: Any,
    private val pngBytes: ByteArray,
    private val captureJson: String = """{"schemaVersion":1}""",
) {
    @Suppress("unused") fun getDocument(): Any = document

    @Suppress("unused") fun getPngBytes(): ByteArray = pngBytes

    @Suppress("unused") fun getCaptureJson(): String = captureJson
}

private class FakeCaptureDocument(private val schemaVersion: Int, private val summary: Any) {
    @Suppress("unused") fun getSchemaVersion(): Int = schemaVersion

    @Suppress("unused") fun getSummary(): Any = summary
}

@Suppress("LongParameterList")
private class FakeCaptureSummary(
    private val nodeCount: Int,
    private val taggedNodeCount: Int,
    private val textedNodeCount: Int,
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val captureDurationMs: Long,
) {
    @Suppress("unused") fun getNodeCount(): Int = nodeCount

    @Suppress("unused") fun getTaggedNodeCount(): Int = taggedNodeCount

    @Suppress("unused") fun getTextedNodeCount(): Int = textedNodeCount

    @Suppress("unused") fun getImageWidth(): Int = imageWidth

    @Suppress("unused") fun getImageHeight(): Int = imageHeight

    @Suppress("unused") fun getCaptureDurationMs(): Long = captureDurationMs
}

private class FakeTrackedWindow(
    private val surfaceIdValue: String,
    private val isPopupValue: Boolean,
    private val composeSurfaceBoundsOnScreenValue: Rectangle,
    private val windowValue: Frame?,
) {
    @Suppress("unused") fun getSurfaceId(): String = surfaceIdValue

    @Suppress("unused") fun isPopup(): Boolean = isPopupValue

    @Suppress("unused")
    fun getComposeSurfaceBoundsOnScreen(): Rectangle = composeSurfaceBoundsOnScreenValue

    // Returns `java.awt.Window?` typed in the real `TrackedWindow.getWindow()` — but Kotlin's
    // reflection sees the declared static return type, so declaring `Frame?` here matches the
    // handler's `(window as? Frame)?.title` shape (and lets the null branch resolve correctly).
    @Suppress("unused") fun getWindow(): Frame? = windowValue
}

@Suppress("LongParameterList")
private class FakeAutomatorNode(
    private val keyValue: String,
    private val testTagValue: String? = null,
    private val textsValue: List<String> = emptyList(),
    private val editableTextValue: String? = null,
    private val roleValue: String? = null,
    private val contentDescriptionValue: String? = null,
    private val isFocusedValue: Boolean = false,
    private val isVisibleValue: Boolean = true,
    private val boundsOnScreenValue: Rectangle = Rectangle(0, 0, 0, 0),
) {
    @Suppress("unused") fun getKey(): String = keyValue

    @Suppress("unused") fun getTestTag(): String? = testTagValue

    @Suppress("unused") fun getTexts(): List<String> = textsValue

    @Suppress("unused") fun getEditableText(): String? = editableTextValue

    @Suppress("unused") fun getRole(): String? = roleValue

    @Suppress("unused") fun getContentDescription(): String? = contentDescriptionValue

    @Suppress("unused") fun isFocused(): Boolean = isFocusedValue

    @Suppress("unused") fun isVisible(): Boolean = isVisibleValue

    @Suppress("unused") fun getBoundsOnScreen(): Rectangle = boundsOnScreenValue
}

private class FakeNodeWithoutVisible(
    private val keyValue: String = "missing-visible",
    private val testTagValue: String? = null,
    private val editableTextValue: String? = null,
    private val roleValue: String? = null,
    private val contentDescriptionValue: String? = null,
    private val isFocusedValue: Boolean = false,
) {
    @Suppress("unused") fun getKey(): String = keyValue

    @Suppress("unused") fun getTestTag(): String? = testTagValue

    @Suppress("unused") fun getTexts(): List<String> = emptyList()

    @Suppress("unused") fun getEditableText(): String? = editableTextValue

    @Suppress("unused") fun getRole(): String? = roleValue

    @Suppress("unused") fun getContentDescription(): String? = contentDescriptionValue

    @Suppress("unused") fun isFocused(): Boolean = isFocusedValue

    @Suppress("unused") fun getBoundsOnScreen(): Rectangle = Rectangle(0, 0, 0, 0)
}

private class FakeSuspendCapableAutomator(private val allNodesValue: List<Any> = emptyList()) {
    val typedTexts = mutableListOf<String>()

    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = allNodesValue

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    // Match Kotlin's bytecode shape for `suspend fun click(node: AutomatorNode)`:
    // `Object click(Object node, kotlin.coroutines.Continuation<? super Unit>)`.
    @Suppress("unused", "UNUSED_PARAMETER")
    fun click(node: Any, continuation: kotlin.coroutines.Continuation<Any?>): Any? = Unit

    @Suppress("unused", "UNUSED_PARAMETER")
    fun typeText(text: String, continuation: kotlin.coroutines.Continuation<Any?>): Any? {
        typedTexts += text
        return Unit
    }
}
