@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit coverage for the pre-`loadAgent` attach race predicate shared by
 * [LaunchReadiness.awaitAgentBootstrap] and the direct-attach integration tests (#443).
 *
 * The predicate must stay narrow. HotSpot reuses `AttachNotSupportedException` for its own attach
 * socket timeout (~10s), and retrying after that terminal timeout would start another full
 * handshake and blow the caller's budget.
 */
class LaunchReadinessAttachRetryTest {
    @Test
    fun `the HotSpot handshake race is retryable`() {
        assertTrue(
            LaunchReadiness.isPreLoadAttachRetryable(
                message =
                    "VirtualMachine.attach(22962) failed: AttachNotSupportedException: " +
                        "pid: 22962, state is not ready to participate in attach handshake!",
                causeMessage = null,
            )
        )
    }

    @Test
    fun `pid churn is retryable`() {
        assertTrue(
            LaunchReadiness.isPreLoadAttachRetryable(
                message = "attach failed: No such process",
                causeMessage = null,
            )
        )
    }

    @Test
    fun `the race is recognised when it only appears on the cause`() {
        assertTrue(
            LaunchReadiness.isPreLoadAttachRetryable(
                message = "VirtualMachine.attach(22962) failed",
                causeMessage = "pid: 22962, state is not ready to participate in attach handshake!",
            )
        )
    }

    @Test
    fun `terminal attach failures are not retryable`() {
        assertFalse(
            LaunchReadiness.isPreLoadAttachRetryable(
                message =
                    "The target JVM does not allow dynamic agent loading. Restart it with " +
                        "`-XX:+EnableDynamicAgentLoading` and retry the attach.",
                causeMessage = "Dynamic agent loading is not enabled",
            )
        )
        assertFalse(
            LaunchReadiness.isPreLoadAttachRetryable(
                message = "Unable to open socket file: target process not responding",
                causeMessage = null,
            )
        )
        assertFalse(LaunchReadiness.isPreLoadAttachRetryable(message = null, causeMessage = null))
        // #454: a terminal AttachNotSupportedException on a dying target is not the handshake
        // race. Extending this predicate would retry attach (including HotSpot's ~10s socket
        // timeout) instead of waiting for the process to exit.
        assertFalse(
            LaunchReadiness.isPreLoadAttachRetryable(
                message =
                    "VirtualMachine.attach(4321) failed: AttachNotSupportedException: not attachable",
                causeMessage = "not attachable",
            )
        )
    }

    @Test
    fun `ordinary AgentLoadException is not a no-live-agent failure`() {
        assertTrue(
            LaunchReadiness.isNoLiveAgentFailure(
                RuntimeException(
                    "VirtualMachine.attach(1) failed: AttachNotSupportedException",
                    AttachNotSupportedException("not attachable"),
                )
            )
        )
        assertFalse(
            LaunchReadiness.isNoLiveAgentFailure(
                RuntimeException(
                    "VirtualMachine.loadAgent(agent.jar) failed: AgentLoadException: not found",
                    AgentLoadException("agent library failed to init"),
                )
            )
        )
        assertTrue(
            LaunchReadiness.isNoLiveAgentFailure(
                RuntimeException(
                    "VirtualMachine.loadAgent(agent.jar) failed: AgentLoadException",
                    AgentLoadException("target process not responding"),
                )
            )
        )
        assertFalse(
            LaunchReadiness.isNoLiveAgentFailure(IllegalStateException("agent jar rejected"))
        )
        assertFalse(
            LaunchReadiness.isNoLiveAgentFailure(
                RuntimeException(
                    "The target JVM does not allow dynamic agent loading. Restart it with " +
                        "`-XX:+EnableDynamicAgentLoading` and retry the attach.",
                    AgentLoadException("Dynamic agent loading is not enabled"),
                )
            )
        )
    }

    private class AttachNotSupportedException(message: String) : RuntimeException(message)

    private class AgentLoadException(message: String) : RuntimeException(message)
}
