@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * #201 wait ops through the real [ReflectiveAutomatorHandler] reflective bridge: success path,
 * Duration packing (packed rawValue, not plain nanoseconds), timeout taxonomy, and invalid-selector
 * rejection. Cancel-while-waiting is covered in [WaitOpsInfrastructureTest] over multiplexed IPC.
 */
class WaitOpsReflectiveHandlerTest {

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
}

/** Name-shape matches core IdleTimeoutException for WaitOps taxonomy matching. */
private class FakeIdleTimeoutException(message: String) : RuntimeException(message)

/**
 * JVM-public stand-in for ComposeAutomator wait surface. Must not be package-private:
 * [BlockingSuspendInvoker] is in this package but Method.invoke still requires a public class.
 */
internal class WaitFakeAutomator(
    private val allNodesValue: List<Any> = emptyList(),
    private val waitForNodeImpl: ((String?, String?, Long, Long) -> Any?)? = null,
) {
    val waitForNodeTags = mutableListOf<String?>()
    val waitForNodeTimeoutRaw = mutableListOf<Long>()

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
    fun waitForVisualIdle(
        timeoutRaw: Long,
        stableFrames: Int,
        pollRaw: Long,
        continuation: kotlin.coroutines.Continuation<Any?>,
    ): Any? = Unit
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
