@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentResponse
import java.lang.reflect.Method

/**
 * Reflective bridge for richer input verbs (#203): doubleClick, longClick, swipe, scrollWheel,
 * pressKey. Kept out of [ReflectiveAutomatorHandler] for detekt size/complexity budgets.
 *
 * Kotlin mangles `Duration` parameters into primitive `long` carrying [Duration]'s packed
 * `rawValue` (not plain nanoseconds). Use [durationStorageFromMs] when invoking those methods.
 */
internal class InputOpsReflectiveMapper(
    private val automator: Any,
    private val suspendInvoker: BlockingSuspendInvoker,
    private val refreshWindows: () -> Unit,
    private val allNodes: () -> List<*>,
    private val extractKey: (Any) -> String,
    private val isTargetJvmFocused: () -> Boolean,
) {
    private val automatorClass: Class<*> = automator.javaClass
    private val longPrimitive: Class<*> =
        Long::class.javaPrimitiveType ?: error("Long primitive type missing")
    private val intPrimitive: Class<*> =
        Int::class.javaPrimitiveType ?: error("Int primitive type missing")

    private val doubleClickMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name == "doubleClick" &&
                it.parameterTypes.size == 2 &&
                it.parameterTypes[1].name == CONTINUATION_FQN
        }

    private val longClickMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name.startsWith("longClick") &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[1] == longPrimitive &&
                it.parameterTypes[2].name == CONTINUATION_FQN
        }

    private val swipeByNodesMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name.startsWith("swipe") &&
                it.parameterTypes.size == 5 &&
                it.parameterTypes[2] == intPrimitive &&
                it.parameterTypes[3] == longPrimitive &&
                it.parameterTypes[4].name == CONTINUATION_FQN
        }

    private val swipeByCoordsMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name.startsWith("swipe") &&
                it.parameterTypes.size == 7 &&
                it.parameterTypes[0] == intPrimitive &&
                it.parameterTypes[1] == intPrimitive &&
                it.parameterTypes[2] == intPrimitive &&
                it.parameterTypes[3] == intPrimitive &&
                it.parameterTypes[4] == intPrimitive &&
                it.parameterTypes[5] == longPrimitive &&
                it.parameterTypes[6].name == CONTINUATION_FQN
        }

    private val scrollWheelMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name == "scrollWheel" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[1] == intPrimitive &&
                it.parameterTypes[2].name == CONTINUATION_FQN
        }

    private val pressKeyMethod: Method? =
        automatorClass.methods.firstOrNull {
            it.name == "pressKey" &&
                it.parameterTypes.size == 3 &&
                it.parameterTypes[0] == intPrimitive &&
                it.parameterTypes[1] == intPrimitive &&
                it.parameterTypes[2].name == CONTINUATION_FQN
        }

    fun handleDoubleClick(nodeKey: String): AgentResponse =
        invokeOnNode(nodeKey = nodeKey, opName = "doubleClick", method = doubleClickMethod)

    fun handleLongClick(request: AgentRequest.LongClick): AgentResponse {
        if (request.holdForMs <= 0L) {
            return AgentResponse.Error(
                message = "holdForMs must be positive",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        val method = longClickMethod ?: return unsupported("longClick(node, holdFor)")
        val node = resolveNode(request.nodeKey) ?: return nodeNotFound(request.nodeKey)
        suspendInvoker.invoke(
            method,
            automator,
            node,
            durationStorageFromMs(request.holdForMs),
            timeoutMsOverride = request.holdForMs + SUSPEND_BRIDGE_SLACK_MS,
        )
        return AgentResponse.Ok
    }

    fun handleSwipe(request: AgentRequest.Swipe): AgentResponse {
        validateSwipeShape(request)?.let {
            return it
        }
        val nodeMode = request.fromNodeKey != null || request.toNodeKey != null
        return if (nodeMode) handleNodeSwipe(request) else handleCoordSwipe(request)
    }

    private fun validateSwipeShape(request: AgentRequest.Swipe): AgentResponse.Error? {
        if (request.steps <= 0) {
            return AgentResponse.Error(
                message = "steps must be positive",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        if (request.durationMs <= 0L) {
            return AgentResponse.Error(
                message = "durationMs must be positive",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        val nodeMode = request.fromNodeKey != null || request.toNodeKey != null
        val coordMode =
            request.startX != null ||
                request.startY != null ||
                request.endX != null ||
                request.endY != null
        if (nodeMode == coordMode) {
            return AgentResponse.Error(
                message =
                    if (nodeMode) "swipe accepts either node keys or coordinates, not both"
                    else "swipe requires fromNodeKey+toNodeKey or start/end coordinates",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        return null
    }

    private fun handleNodeSwipe(request: AgentRequest.Swipe): AgentResponse {
        val fromKey =
            request.fromNodeKey
                ?: return AgentResponse.Error(
                    message = "fromNodeKey is required for node swipe",
                    category = AgentErrorCategory.InvalidSelector.wireName,
                )
        val toKey =
            request.toNodeKey
                ?: return AgentResponse.Error(
                    message = "toNodeKey is required for node swipe",
                    category = AgentErrorCategory.InvalidSelector.wireName,
                )
        val method = swipeByNodesMethod ?: return unsupported("swipe(from, to, steps, duration)")
        val from = resolveNode(fromKey) ?: return nodeNotFound(fromKey)
        val to = resolveNode(toKey) ?: return nodeNotFound(toKey)
        suspendInvoker.invoke(
            method,
            automator,
            from,
            to,
            request.steps,
            durationStorageFromMs(request.durationMs),
            timeoutMsOverride = request.durationMs + SUSPEND_BRIDGE_SLACK_MS,
        )
        return AgentResponse.Ok
    }

    private fun handleCoordSwipe(request: AgentRequest.Swipe): AgentResponse {
        val startX = request.startX
        val startY = request.startY
        val endX = request.endX
        val endY = request.endY
        if (startX == null || startY == null || endX == null || endY == null) {
            return AgentResponse.Error(
                message = "swipe coordinates require startX, startY, endX, and endY",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        val method =
            swipeByCoordsMethod
                ?: return unsupported("swipe(startX, startY, endX, endY, steps, duration)")
        refreshWindows()
        suspendInvoker.invoke(
            method,
            automator,
            startX,
            startY,
            endX,
            endY,
            request.steps,
            durationStorageFromMs(request.durationMs),
            timeoutMsOverride = request.durationMs + SUSPEND_BRIDGE_SLACK_MS,
        )
        return AgentResponse.Ok
    }

    fun handleScrollWheel(request: AgentRequest.ScrollWheel): AgentResponse {
        val method = scrollWheelMethod ?: return unsupported("scrollWheel(node, wheelClicks)")
        val node = resolveNode(request.nodeKey) ?: return nodeNotFound(request.nodeKey)
        suspendInvoker.invoke(method, automator, node, request.wheelClicks)
        return AgentResponse.Ok
    }

    fun handlePressKey(request: AgentRequest.PressKey): AgentResponse {
        // AWT Robot rejects keyCode 0; if modifiers were pressed first they can stick (RobotDriver
        // releases in a non-finally path). Reject before dispatching.
        if (request.keyCode <= 0) {
            return AgentResponse.Error(
                message = "keyCode must be a positive AWT VK_* constant",
                category = AgentErrorCategory.InvalidSelector.wireName,
            )
        }
        val method = pressKeyMethod ?: return unsupported("pressKey(keyCode, modifiers)")
        // Real OS key events require the target JVM to own keyboard focus (same guard as typeText).
        if (!isTargetJvmFocused()) {
            return AgentResponse.Error(
                message =
                    "Refusing pressKey because the target JVM does not currently own OS keyboard " +
                        "focus. Activate the target window before sending real keyboard events.",
                category = AgentErrorCategory.InputRejected.wireName,
            )
        }
        refreshWindows()
        suspendInvoker.invoke(method, automator, request.keyCode, request.modifiers)
        return AgentResponse.Ok
    }

    private fun invokeOnNode(nodeKey: String, opName: String, method: Method?): AgentResponse {
        val m = method ?: return unsupported("$opName(node)")
        val node = resolveNode(nodeKey) ?: return nodeNotFound(nodeKey)
        suspendInvoker.invoke(m, automator, node)
        return AgentResponse.Ok
    }

    private fun resolveNode(nodeKey: String): Any? {
        refreshWindows()
        return allNodes().firstOrNull { it != null && extractKey(it) == nodeKey }
    }

    private fun nodeNotFound(nodeKey: String): AgentResponse.Error =
        AgentResponse.Error(
            message = "No node found with key=$nodeKey",
            category = AgentErrorCategory.NodeNotFound.wireName,
        )

    private fun unsupported(signature: String): AgentResponse.Error =
        AgentResponse.Error(
            message = "ComposeAutomator does not expose $signature",
            category = AgentErrorCategory.UnsupportedOperation.wireName,
        )

    private companion object {
        const val CONTINUATION_FQN: String = "kotlin.coroutines.Continuation"
        /** Extra budget so the suspend bridge outlives short holds/swipes. */
        const val SUSPEND_BRIDGE_SLACK_MS: Long = 5_000

        /**
         * Packs milliseconds into the `long` storage used by Kotlin [kotlin.time.Duration] on the
         * JVM (nanos << 1 for small values; millis << 1 | 1 when nanos would overflow).
         *
         * Reflective `Method.invoke` must pass this packing — not [Duration.inWholeNanoseconds] —
         * or longClick/swipe holds are half the intended length.
         */
        fun durationStorageFromMs(ms: Long): Long {
            require(ms >= 0L) { "duration ms must be non-negative" }
            val nanos = ms * 1_000_000L
            // Matches Duration's "fits in nanos" threshold (Long.MAX_VALUE / 2).
            return if (nanos <= Long.MAX_VALUE / 2) nanos shl 1 else (ms shl 1) + 1
        }
    }
}
