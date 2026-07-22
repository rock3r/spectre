@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import java.lang.reflect.Method
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reflective bridge for [AgentRequest.WaitForNode] / [AgentRequest.WaitForVisualIdle] (#201). Lives
 * outside [ReflectiveAutomatorHandler] to keep that class under detekt complexity limits.
 *
 * Kotlin mangles `Duration` parameters into primitive `long` (nanoseconds) and renames methods
 * (e.g. `waitForNode-ck1zr5g`). Lookups use parameter types, not the unmangled source name.
 */
internal class WaitOpsReflectiveMapper(
    private val automator: Any,
    private val suspendInvoker: BlockingSuspendInvoker,
    private val mapNode: (Any) -> NodeSnapshotDto,
) {
    private val automatorClass: Class<*> = automator.javaClass
    private val longPrimitive: Class<*> =
        Long::class.javaPrimitiveType ?: error("Long primitive type missing")
    private val intPrimitive: Class<*> =
        Int::class.javaPrimitiveType ?: error("Int primitive type missing")

    private val waitForNodeSuspendMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name.startsWith("waitForNode") &&
                it.parameterTypes.size == 5 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                it.parameterTypes[2] == longPrimitive &&
                it.parameterTypes[3] == longPrimitive &&
                it.parameterTypes[4].name == CONTINUATION_FQN
        }

    private val waitForVisualIdleSuspendMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name.startsWith("waitForVisualIdle") &&
                it.parameterTypes.size == 4 &&
                it.parameterTypes[0] == longPrimitive &&
                it.parameterTypes[1] == intPrimitive &&
                it.parameterTypes[2] == longPrimitive &&
                it.parameterTypes[3].name == CONTINUATION_FQN
        }

    fun handleWaitForNode(request: AgentRequest.WaitForNode): AgentResponse {
        if (request.tag == null && request.text == null) {
            return AgentResponse.Error(
                message = "Either tag or text must be specified",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        val method =
            waitForNodeSuspendMethod
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose waitForNode",
                    category = AgentErrorCategory.UnsupportedOperation.wireName,
                )
        val timeoutMs = request.timeoutMs ?: DEFAULT_WAIT_TIMEOUT_MS
        val pollMs = request.pollIntervalMs ?: DEFAULT_WAIT_POLL_MS
        return runWait {
            val node =
                suspendInvoker.invoke(
                    method,
                    automator,
                    request.tag,
                    request.text,
                    // Duration is a value class: JVM surface is primitive long nanoseconds.
                    timeoutMs.milliseconds.inWholeNanoseconds,
                    pollMs.milliseconds.inWholeNanoseconds,
                    timeoutMsOverride = timeoutMs + SUSPEND_BRIDGE_SLACK_MS,
                )
            if (node == null) {
                AgentResponse.Error(
                    message = "waitForNode returned null",
                    category = AgentErrorCategory.InternalError.wireName,
                )
            } else {
                AgentResponse.Nodes(listOf(mapNode(node)))
            }
        }
    }

    fun handleWaitForVisualIdle(request: AgentRequest.WaitForVisualIdle): AgentResponse {
        val method =
            waitForVisualIdleSuspendMethod
                ?: return AgentResponse.Error(
                    message = "ComposeAutomator does not expose waitForVisualIdle",
                    category = AgentErrorCategory.UnsupportedOperation.wireName,
                )
        val timeoutMs = request.timeoutMs ?: DEFAULT_WAIT_TIMEOUT_MS
        val pollMs = request.pollIntervalMs ?: DEFAULT_VISUAL_POLL_MS
        val stableFrames = request.stableFrames ?: DEFAULT_STABLE_FRAMES
        return runWait {
            suspendInvoker.invoke(
                method,
                automator,
                timeoutMs.milliseconds.inWholeNanoseconds,
                stableFrames,
                pollMs.milliseconds.inWholeNanoseconds,
                timeoutMsOverride = timeoutMs + SUSPEND_BRIDGE_SLACK_MS,
            )
            AgentResponse.Ok
        }
    }

    /**
     * Maps wait-loop timeouts (stdlib [TimeoutException], IdleTimeoutException,
     * TimeoutCancellationException) to taxonomy `timeout`; rethrows everything else.
     */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun runWait(block: () -> AgentResponse): AgentResponse =
        try {
            block()
        } catch (ex: Exception) {
            // IdleTimeoutException / TimeoutCancellationException are not on the classpath of
            // :agent; match by type name so wait timeouts become taxonomy `timeout`.
            if (ex is java.util.concurrent.TimeoutException || isWaitTimeout(ex)) {
                timeoutError(ex)
            } else {
                throw ex
            }
        }

    private fun timeoutError(ex: Exception): AgentResponse.Error =
        AgentResponse.Error(
            message = "${ex.javaClass.simpleName}: ${ex.message ?: "<no message>"}",
            category = AgentErrorCategory.Timeout.wireName,
        )

    private companion object {
        const val CONTINUATION_FQN: String = "kotlin.coroutines.Continuation"
        const val DEFAULT_WAIT_TIMEOUT_MS: Long = 5_000
        const val DEFAULT_WAIT_POLL_MS: Long = 100
        const val DEFAULT_VISUAL_POLL_MS: Long = 16
        const val DEFAULT_STABLE_FRAMES: Int = 3
        const val SUSPEND_BRIDGE_SLACK_MS: Long = 2_000

        fun isWaitTimeout(ex: Exception): Boolean {
            val name = ex.javaClass.name
            val msg = ex.message.orEmpty().lowercase()
            return name.endsWith("IdleTimeoutException") ||
                name.endsWith("TimeoutCancellationException") ||
                "timed out" in msg ||
                "timeout" in name.lowercase()
        }
    }
}
