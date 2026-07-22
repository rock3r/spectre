@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.NodeSnapshotDto
import java.lang.reflect.Method

/**
 * Reflective bridge for [AgentRequest.WaitForNode] / [AgentRequest.WaitForVisualIdle] (#201). Lives
 * outside [ReflectiveAutomatorHandler] to keep that class under detekt complexity limits.
 *
 * Kotlin mangles `Duration` parameters into primitive `long` carrying [kotlin.time.Duration]'s
 * packed `rawValue` (not plain nanoseconds) and renames methods (e.g. `waitForNode-ck1zr5g`).
 * Lookups use parameter types, not the unmangled source name; invocations use
 * [durationStorageFromMs].
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
                    // Duration is a value class: JVM surface is packed rawValue, not plain nanos.
                    durationStorageFromMs(timeoutMs),
                    durationStorageFromMs(pollMs),
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
                durationStorageFromMs(timeoutMs),
                stableFrames,
                durationStorageFromMs(pollMs),
                timeoutMsOverride = timeoutMs + SUSPEND_BRIDGE_SLACK_MS,
            )
            AgentResponse.Ok
        }
    }

    /**
     * Maps wait-loop timeouts (stdlib [TimeoutException], IdleTimeoutException,
     * TimeoutCancellationException) to taxonomy `timeout`; rethrows everything else.
     *
     * Reflective [java.lang.reflect.Method.invoke] wraps checked/runtime failures in
     * [java.lang.reflect.InvocationTargetException]; unwrap so taxonomy matching sees the
     * automator's real exception type/message.
     */
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun runWait(block: () -> AgentResponse): AgentResponse =
        try {
            block()
        } catch (ex: Exception) {
            val root = unwrapReflective(ex)
            // IdleTimeoutException / TimeoutCancellationException are not on the classpath of
            // :agent; match by type name so wait timeouts become taxonomy `timeout`.
            if (root is java.util.concurrent.TimeoutException || isWaitTimeout(root)) {
                timeoutError(root)
            } else {
                throw ex
            }
        }

    private fun timeoutError(ex: Throwable): AgentResponse.Error =
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

        fun unwrapReflective(ex: Exception): Throwable {
            var current: Throwable = ex
            while (current is java.lang.reflect.InvocationTargetException) {
                current = current.cause ?: return current
            }
            return current
        }

        fun isWaitTimeout(ex: Throwable): Boolean {
            val name = ex.javaClass.name
            val msg = ex.message.orEmpty().lowercase()
            return name.endsWith("IdleTimeoutException") ||
                name.endsWith("TimeoutCancellationException") ||
                "timed out" in msg ||
                "timeout" in name.lowercase()
        }

        /**
         * Packs milliseconds into the `long` storage used by Kotlin [kotlin.time.Duration] on the
         * JVM (nanos << 1 for small values; millis << 1 | 1 when nanos would overflow).
         *
         * Must match [InputOpsReflectiveMapper]'s packing — plain
         * [kotlin.time.Duration.inWholeNanoseconds] is half the intended length once Duration
         * reinterprets the low unit bit.
         */
        fun durationStorageFromMs(ms: Long): Long {
            require(ms >= 0L) { "duration ms must be non-negative" }
            // Compare against the nanos-storage threshold in ms-space first so
            // `ms * NANOS_PER_MS` cannot overflow Long before the millis-unit branch.
            val maxMsStoredAsNanos = (Long.MAX_VALUE / 2) / NANOS_PER_MS
            return if (ms <= maxMsStoredAsNanos) {
                (ms * NANOS_PER_MS) shl 1
            } else {
                (ms shl 1) + 1
            }
        }

        private const val NANOS_PER_MS: Long = 1_000_000L
    }
}
