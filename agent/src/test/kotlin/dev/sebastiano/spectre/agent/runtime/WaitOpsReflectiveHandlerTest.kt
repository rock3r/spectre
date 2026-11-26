@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #201 wait ops through the real [ReflectiveAutomatorHandler] reflective bridge: success path,
 * Duration packing (packed rawValue, not plain nanoseconds), timeout taxonomy, and invalid-selector
 * rejection. Cancel-while-waiting is covered in [WaitOpsInfrastructureTest] over multiplexed IPC.
 */
class WaitOpsReflectiveHandlerTest {

    @Test
    fun `WaitForVisualIdle refuses fail-closed policy on a legacy target`() {
        val response =
            ReflectiveAutomatorHandler(WaitFakeAutomator())
                .handle(AgentRequest.WaitForVisualIdle(allowScreenRegionFallback = false))

        check(response is AgentResponse.Error) { "expected Error, got $response" }
        assertEquals(AgentErrorCategory.UnsupportedOperation.wireName, response.category)
        assertTrue(response.message.contains("cannot enforce"), response.message)
    }

    @Test
    fun `WaitForVisualIdle permits explicit fallback on a legacy target`() {
        val fake = WaitFakeAutomator()
        val response =
            ReflectiveAutomatorHandler(fake)
                .handle(AgentRequest.WaitForVisualIdle(allowScreenRegionFallback = true))

        check(response is AgentResponse.Ok) { "expected Ok, got $response" }
        assertEquals(1, fake.legacyVisualIdleCalls)
    }

    @Test
    fun `WaitForVisualIdle prefers modern overload declared after legacy for both policies`() {
        assertModernOverloadSelected(ModernAfterLegacyFake())
    }

    @Test
    fun `WaitForVisualIdle prefers modern overload declared before legacy for both policies`() {
        assertModernOverloadSelected(ModernBeforeLegacyFake())
    }

    private fun assertModernOverloadSelected(fake: BothVisualIdleOverloadsFake) {
        val handler = ReflectiveAutomatorHandler(fake)
        listOf(false, true).forEach { allowFallback ->
            val response =
                handler.handle(
                    AgentRequest.WaitForVisualIdle(allowScreenRegionFallback = allowFallback)
                )
            check(response is AgentResponse.Ok) { "expected Ok, got $response" }
        }
        assertEquals(listOf(false, true), fake.modernFallbackArguments)
        assertEquals(0, fake.legacyVisualIdleCalls)
    }

    @Test
    fun `WaitForNode success returns the matched node`() {
        val node = WaitFakeNode(keyValue = "btn", testTagValue = "Counter")
        val fake = WaitFakeAutomator(allNodesValue = listOf(node))
        val handler = ReflectiveAutomatorHandler(fake)

        val response = handler.handle(AgentRequest.WaitForNode(tag = "Counter", timeoutMs = 1_000))
        check(response is AgentResponse.Nodes) { "expected Nodes, got $response" }
        assertEquals(1, response.nodes.size)
        assertEquals("btn", response.nodes.single().key)
        assertEquals(listOf<String?>("Counter"), fake.waitForNodeTags.toList())
        // Packed Duration rawValue for 1000ms: nanos << 1 = 1_000_000_000 << 1.
        val expectedTimeoutRaw = 1_000L * 1_000_000L shl 1
        assertEquals(listOf(expectedTimeoutRaw), fake.waitForNodeTimeoutRaw.toList())
    }

    @Test
    fun `WaitForNode timeout maps to taxonomy timeout`() {
        val fake =
            WaitFakeAutomator(
                waitForNodeImpl = { _: String?, _: String?, _: Long, _: Long ->
                    throw FakeIdleTimeoutException("waitForNode timed out after 400 ms")
                }
            )
        val handler = ReflectiveAutomatorHandler(fake)

        val response =
            handler.handle(
                AgentRequest.WaitForNode(
                    tag = "never-appears",
                    timeoutMs = 400,
                    pollIntervalMs = 50,
                )
            )
        check(response is AgentResponse.Error) { "expected Error, got $response" }
        assertEquals(AgentErrorCategory.Timeout.wireName, response.category)
        // 400ms → nanos << 1 packing (not plain inWholeNanoseconds).
        val expectedTimeoutRaw = 400L * 1_000_000L shl 1
        assertEquals(listOf(expectedTimeoutRaw), fake.waitForNodeTimeoutRaw.toList())
    }

    @Test
    fun `WaitForNode refuses when neither tag nor text is set`() {
        val handler = ReflectiveAutomatorHandler(WaitFakeAutomator())
        val response = handler.handle(AgentRequest.WaitForNode(tag = null, text = null))
        check(response is AgentResponse.Error)
        assertEquals(AgentErrorCategory.InvalidSelector.wireName, response.category)
    }

    @Test
    fun `WaitUntilGone success returns Ok with packed Duration rawValues`() {
        val fake = WaitFakeAutomator()
        val handler = ReflectiveAutomatorHandler(fake)

        val response =
            handler.handle(
                AgentRequest.WaitUntilGone(
                    tag = "popup.body",
                    timeoutMs = 1_000,
                    pollIntervalMs = 50,
                )
            )
        check(response is AgentResponse.Ok) { "expected Ok, got $response" }
        assertEquals(listOf<String?>("popup.body"), fake.waitUntilGoneTags.toList())
        // Packed Duration rawValue for 1000ms / 50ms: nanos << 1, not plain inWholeNanoseconds.
        assertEquals(listOf(1_000L * 1_000_000L shl 1), fake.waitUntilGoneTimeoutRaw.toList())
        assertEquals(listOf(50L * 1_000_000L shl 1), fake.waitUntilGonePollRaw.toList())
    }

    @Test
    fun `WaitUntilGone timeout keeps selector, timeout, and still-present count in the message`() {
        val stillPresent =
            """waitUntilGone timed out after 400ms: 2 node(s) matching tag="popup.body" """ +
                "still present in tracked windows"
        val fake =
            WaitFakeAutomator(
                waitUntilGoneImpl = { _: String?, _: String?, _: Long, _: Long ->
                    // Core raises a plain IllegalStateException carrying the absence diagnostics.
                    error(stillPresent)
                }
            )
        val handler = ReflectiveAutomatorHandler(fake)

        val response =
            handler.handle(
                AgentRequest.WaitUntilGone(tag = "popup.body", timeoutMs = 400, pollIntervalMs = 50)
            )
        check(response is AgentResponse.Error) { "expected Error, got $response" }
        assertEquals(AgentErrorCategory.Timeout.wireName, response.category)
        assertTrue(
            response.message.contains(stillPresent),
            "absence diagnostics degraded to \"${response.message}\"",
        )
    }

    @Test
    fun `WaitUntilGone refuses when neither tag nor text is set`() {
        val handler = ReflectiveAutomatorHandler(WaitFakeAutomator())
        val response = handler.handle(AgentRequest.WaitUntilGone(tag = null, text = null))
        check(response is AgentResponse.Error)
        assertEquals(AgentErrorCategory.InvalidSelector.wireName, response.category)
    }

    @Test
    fun `WaitForIdle success invokes target-side wait with packed Duration rawValues`() {
        val fake = WaitFakeAutomator()
        val handler = ReflectiveAutomatorHandler(fake)

        val response =
            handler.handle(
                AgentRequest.WaitForIdle(timeoutMs = 2_000, quietPeriodMs = 64, pollIntervalMs = 16)
            )
        check(response is AgentResponse.Ok) { "expected Ok, got $response" }
        val expectedTimeoutRaw = 2_000L * 1_000_000L shl 1
        val expectedQuietRaw = 64L * 1_000_000L shl 1
        val expectedPollRaw = 16L * 1_000_000L shl 1
        assertEquals(listOf(expectedTimeoutRaw), fake.waitForIdleTimeoutRaw.toList())
        assertEquals(listOf(expectedQuietRaw), fake.waitForIdleQuietRaw.toList())
        assertEquals(listOf(expectedPollRaw), fake.waitForIdlePollRaw.toList())
    }

    @Test
    fun `WaitForIdle timeout maps to taxonomy timeout`() {
        val fake =
            WaitFakeAutomator(
                waitForIdleImpl = { _: Long, _: Long, _: Long ->
                    throw FakeIdleTimeoutException("waitForIdle timed out after 300ms")
                }
            )
        val handler = ReflectiveAutomatorHandler(fake)

        val response = handler.handle(AgentRequest.WaitForIdle(timeoutMs = 300, quietPeriodMs = 50))
        check(response is AgentResponse.Error) { "expected Error, got $response" }
        assertEquals(AgentErrorCategory.Timeout.wireName, response.category)
    }
}

/** Name-shape matches core IdleTimeoutException for WaitOps taxonomy matching. */
private class FakeIdleTimeoutException(message: String) : RuntimeException(message)

/**
 * JVM-public stand-in for ComposeAutomator wait surface. Must not be package-private:
 * [BlockingSuspendInvoker] is in this package but Method.invoke still requires a public class.
 */
internal open class WaitFakeAutomator(
    private val allNodesValue: List<Any> = emptyList(),
    private val waitForNodeImpl: ((String?, String?, Long, Long) -> Any?)? = null,
    private val waitForIdleImpl: ((Long, Long, Long) -> Any?)? = null,
    private val waitUntilGoneImpl: ((String?, String?, Long, Long) -> Any?)? = null,
    private val waitForVisualIdleImpl: ((Long, Int, Long) -> Any?)? = null,
) {
    var legacyVisualIdleCalls = 0
    val waitForNodeTags = mutableListOf<String?>()
    val waitForNodeTimeoutRaw = mutableListOf<Long>()
    val waitForIdleTimeoutRaw = mutableListOf<Long>()
    val waitForIdleQuietRaw = mutableListOf<Long>()
    val waitForIdlePollRaw = mutableListOf<Long>()
    val waitUntilGoneTags = mutableListOf<String?>()
    val waitUntilGoneTimeoutRaw = mutableListOf<Long>()
    val waitUntilGonePollRaw = mutableListOf<Long>()

    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = allNodesValue

    @Suppress("unused") fun findByTestTag(tag: String): List<Any> = emptyList()

    @Suppress("unused", "UNUSED_PARAMETER")
    fun waitForNode(
        tag: String?,
        text: String?,
        timeoutRaw: Long,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? {
        waitForNodeTags += tag
        waitForNodeTimeoutRaw += timeoutRaw
        waitForNodeImpl?.let {
            return it(tag, text, timeoutRaw, pollRaw)
        }
        return allNodesValue.firstOrNull { node ->
            val nodeTag =
                node.javaClass.methods
                    .firstOrNull { it.name == "getTestTag" && it.parameterCount == 0 }
                    ?.invoke(node) as? String
            tag != null && nodeTag == tag
        }
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    open fun waitForVisualIdle(
        timeoutRaw: Long,
        stableFrames: Int,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? {
        legacyVisualIdleCalls++
        waitForVisualIdleImpl?.let {
            return it(timeoutRaw, stableFrames, pollRaw)
        }
        return Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun waitForIdle(
        timeoutRaw: Long,
        quietRaw: Long,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? {
        waitForIdleTimeoutRaw += timeoutRaw
        waitForIdleQuietRaw += quietRaw
        waitForIdlePollRaw += pollRaw
        waitForIdleImpl?.let {
            return it(timeoutRaw, quietRaw, pollRaw)
        }
        return Unit
    }

    @Suppress("unused", "UNUSED_PARAMETER")
    fun waitUntilGone(
        tag: String?,
        text: String?,
        timeoutRaw: Long,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? {
        waitUntilGoneTags += tag
        waitUntilGoneTimeoutRaw += timeoutRaw
        waitUntilGonePollRaw += pollRaw
        waitUntilGoneImpl?.let {
            return it(tag, text, timeoutRaw, pollRaw)
        }
        return Unit
    }
}

internal interface BothVisualIdleOverloadsFake {
    val modernFallbackArguments: MutableList<Boolean>
    val legacyVisualIdleCalls: Int
}

internal class ModernAfterLegacyFake : WaitFakeAutomator(), BothVisualIdleOverloadsFake {
    override fun waitForVisualIdle(
        timeoutRaw: Long,
        stableFrames: Int,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? = super.waitForVisualIdle(timeoutRaw, stableFrames, pollRaw, continuation)

    override val modernFallbackArguments = mutableListOf<Boolean>()

    @Suppress("unused", "UNUSED_PARAMETER")
    fun waitForVisualIdle(
        timeoutRaw: Long,
        stableFrames: Int,
        pollRaw: Long,
        allowScreenRegionFallback: Boolean,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? {
        modernFallbackArguments += allowScreenRegionFallback
        return Unit
    }
}

internal class ModernBeforeLegacyFake : WaitFakeAutomator(), BothVisualIdleOverloadsFake {
    override val modernFallbackArguments = mutableListOf<Boolean>()

    @Suppress("unused", "UNUSED_PARAMETER")
    fun waitForVisualIdle(
        timeoutRaw: Long,
        stableFrames: Int,
        pollRaw: Long,
        allowScreenRegionFallback: Boolean,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? {
        modernFallbackArguments += allowScreenRegionFallback
        return Unit
    }

    override fun waitForVisualIdle(
        timeoutRaw: Long,
        stableFrames: Int,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? = super.waitForVisualIdle(timeoutRaw, stableFrames, pollRaw, continuation)
}

internal class WaitFakeNode(
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
