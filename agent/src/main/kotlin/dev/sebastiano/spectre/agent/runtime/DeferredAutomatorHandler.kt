@file:OptIn(dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.runtime

import dev.sebastiano.spectre.agent.transport.AgentErrorCategory
import dev.sebastiano.spectre.agent.transport.AgentRequest
import dev.sebastiano.spectre.agent.transport.AgentRequestHandler
import dev.sebastiano.spectre.agent.transport.AgentResponse
import dev.sebastiano.spectre.agent.transport.ProtocolVersion

/**
 * A `ComposeAutomator` built in the target, with the handler that serves requests against it and
 * the target-side input resource that has to be closed when the attach ends.
 */
internal class ConnectedAutomator(
    val automator: Any,
    val handler: AgentRequestHandler,
    val targetInputResource: AutoCloseable?,
)

/**
 * Serves an attach to an IntelliJ IDE that has not loaded Compose yet (#574).
 *
 * An IDE loads Compose only when the first Compose surface opens. Until then this handler answers
 * as a target with no Compose UI: window and node queries return empty lists, and operations on a
 * node, a window, or keyboard focus fail. Each request first calls [connect], which returns null
 * while Compose is still missing. Once it returns an automator, every later request goes to that
 * automator's handler, and [connect] is not called again.
 *
 * `waitForNode` waits for Compose too: it keeps calling [connect] until its timeout ends, then
 * passes the time left to the automator, so a caller can wait for a node in a Compose tool window
 * that has not opened yet.
 *
 * [close] closes the automator's input resource, and stops this handler from building one after the
 * attach has ended.
 */
internal class DeferredAutomatorHandler(
    private val connect: () -> ConnectedAutomator?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : AgentRequestHandler, AutoCloseable {
    private val lock = Any()
    @Volatile private var connected: ConnectedAutomator? = null
    @Volatile private var closed = false

    override fun handle(request: AgentRequest): AgentResponse {
        connectedOrNull()?.let {
            return it.handler.handle(request)
        }
        return when (request) {
            is AgentRequest.WaitForNode -> waitForComposeThen(request)
            else -> answerWithoutCompose(request)
        }
    }

    override fun close() {
        val toClose =
            synchronized(lock) {
                closed = true
                connected.also { connected = null }
            }
        toClose?.targetInputResource?.close()
    }

    private fun connectedOrNull(): ConnectedAutomator? {
        connected?.let {
            return it
        }
        return synchronized(lock) {
            if (closed) null else connected ?: connect()?.also { connected = it }
        }
    }

    private fun waitForComposeThen(request: AgentRequest.WaitForNode): AgentResponse {
        val timeoutMs = request.timeoutMs ?: DEFAULT_WAIT_TIMEOUT_MS
        val pollMs = (request.pollIntervalMs ?: DEFAULT_POLL_INTERVAL_MS).coerceAtLeast(1)
        val deadline = clock() + timeoutMs
        var automator: ConnectedAutomator? = null
        while (automator == null) {
            val remaining = deadline - clock()
            if (remaining <= 0) {
                return errorResponse(
                    AgentErrorCategory.Timeout,
                    "waitForNode timed out after $timeoutMs ms: $NO_COMPOSE_YET",
                )
            }
            sleep(minOf(pollMs, remaining))
            automator = connectedOrNull()
        }
        val left = (deadline - clock()).coerceAtLeast(0)
        return automator.handler.handle(request.copy(timeoutMs = left))
    }

    @Suppress("CyclomaticComplexMethod") // Exhaustive wire dispatch table.
    private fun answerWithoutCompose(request: AgentRequest): AgentResponse =
        when (request) {
            AgentRequest.Ping -> AgentResponse.Pong
            AgentRequest.Windows -> AgentResponse.Windows(emptyList())
            AgentRequest.AllNodes,
            is AgentRequest.FindByTestTag,
            is AgentRequest.FindByText,
            is AgentRequest.FindByContentDescription,
            is AgentRequest.FindByRole -> AgentResponse.Nodes(emptyList())
            is AgentRequest.WindowIdentity -> AgentResponse.WindowIdentities(emptyList())
            AgentRequest.PrintTree -> AgentResponse.TreeDump("")
            // With no Compose UI nothing can match, change, or animate.
            is AgentRequest.WaitUntilGone,
            is AgentRequest.WaitForIdle,
            is AgentRequest.WaitForVisualIdle,
            is AgentRequest.Cancel -> AgentResponse.Ok
            is AgentRequest.WaitForNode -> waitForComposeThen(request)
            is AgentRequest.Click,
            is AgentRequest.DoubleClick,
            is AgentRequest.LongClick,
            is AgentRequest.Swipe,
            is AgentRequest.ScrollWheel,
            is AgentRequest.FocusWindow,
            is AgentRequest.Screenshot,
            is AgentRequest.Capture,
            is AgentRequest.CaptureWithWindowStill,
            is AgentRequest.CaptureWithUntitledWindowStill ->
                errorResponse(AgentErrorCategory.NodeNotFound, NO_COMPOSE_YET)
            is AgentRequest.PressKey,
            is AgentRequest.TypeText ->
                errorResponse(AgentErrorCategory.InputRejected, NO_COMPOSE_YET)
            AgentRequest.Detach -> AgentResponse.Detached
            // Handled in IpcServer before any handler; keep exhaustive.
            is AgentRequest.Hello -> AgentResponse.HelloAck(ProtocolVersion.CURRENT)
        }

    private fun errorResponse(category: AgentErrorCategory, message: String): AgentResponse =
        AgentResponse.Error(message = message, category = category.wireName)

    private companion object {
        // Same defaults as ComposeAutomator.waitForNode, which serves the request once connected.
        const val DEFAULT_WAIT_TIMEOUT_MS: Long = 5_000
        const val DEFAULT_POLL_INTERVAL_MS: Long = 100
        const val NO_COMPOSE_YET =
            "No Compose UI has loaded in the target JVM yet. Open a Compose surface and retry."
    }
}
