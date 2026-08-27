@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * How [ReflectiveAutomatorHandler] classifies a `:core` guard that throws **after a suspension
 * point** — the shape #460's input-delivery verification has.
 *
 * Such a guard resumes the continuation with failure instead of throwing through `Method.invoke`,
 * so [BlockingSuspendInvoker] rethrows it raw from `Result.getOrThrow()` with no
 * `ReflectiveOperationException` wrapper. The handler's reflective-exception branch therefore
 * cannot see it, and without a dedicated branch the very failure #460's fix raises would reach the
 * attacher as an `internalError`.
 *
 * Lives apart from `ReflectiveAutomatorHandlerMappingTest` because that class is already at the
 * project's size ceiling.
 */
class ReflectiveAutomatorHandlerInputRejectionTest {

    @Test
    fun `input rejection thrown after a suspension point maps to inputRejected`() {
        val response =
            clickFailingAfterSuspension(
                IllegalStateException(
                    "Spectre dispatched a click at (1, 2) but no mouse event was delivered to " +
                        "this JVM. Real OS input requires the target process to be on the " +
                        "session's active input desktop."
                )
            )

        check(response is AgentResponse.Error) { "expected an Error response, got $response" }
        assertEquals(AgentErrorCategory.InputRejected.wireName, response.category)
    }

    /**
     * The narrowness matters as much as the catch: every other [IllegalStateException] must keep
     * propagating out of `handle`, because the reflective layer uses it to fail loudly on automator
     * API mismatches and that contract is asserted elsewhere.
     */
    @Test
    fun `unrelated failure after a suspension point still propagates`() {
        assertFailsWith<IllegalStateException> {
            clickFailingAfterSuspension(IllegalStateException("something else broke"))
        }
    }

    private fun clickFailingAfterSuspension(failure: IllegalStateException): AgentResponse {
        val automator = ClickFailingAfterSuspensionAutomator(FakeKeyedNode("k1"), failure)
        return ReflectiveAutomatorHandler(automator).handle(AgentRequest.Click(nodeKey = "k1"))
    }
}

/** Minimal stand-in for `AutomatorNode`: the handler only needs `getKey()` to resolve it. */
private class FakeKeyedNode(private val keyValue: String) {
    @Suppress("unused") fun getKey(): String = keyValue
}

/**
 * Exposes exactly the method set [ReflectiveAutomatorHandler]'s constructor requires, and fails the
 * click the way a real post-suspension guard does: resume the continuation with the failure, then
 * report having suspended.
 */
private class ClickFailingAfterSuspensionAutomator(
    private val node: Any,
    private val failure: Throwable,
) {
    @Suppress("unused") fun refreshWindows() = Unit

    @Suppress("unused") fun getWindows(): List<Any> = emptyList()

    @Suppress("unused") fun allNodes(): List<Any> = listOf(node)

    @Suppress("unused", "UNUSED_PARAMETER") fun findByTestTag(tag: String): List<Any> = emptyList()

    // Matches Kotlin's bytecode shape for `suspend fun click(node: AutomatorNode)`.
    @Suppress("unused", "UNUSED_PARAMETER")
    fun click(node: Any, continuation: Continuation<Any?>): Any? {
        continuation.resumeWith(Result.failure(failure))
        return COROUTINE_SUSPENDED
    }
}
