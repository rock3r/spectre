package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

/**
 * Per-stage timeouts for [LaunchAndAttach.launch] readiness.
 *
 * Defaults favour CI-friendly Compose Desktop fixture boots without sleep-and-pray polling of the
 * whole pipeline as one opaque budget.
 */
@ExperimentalSpectreAgentApi
public data class LaunchStageTimeouts(
    /** How long the process may take to stay alive after [ProcessBuilder.start]. */
    public val processAliveMs: Long = DEFAULT_PROCESS_ALIVE_MS,
    /** How long to wait for the target JVM to appear in Attach-API listings. */
    public val jvmAttachableMs: Long = DEFAULT_JVM_ATTACHABLE_MS,
    /** How long agent load + bootstrap + UDS connect may take. */
    public val agentBootstrapMs: Long = DEFAULT_AGENT_BOOTSTRAP_MS,
    /** How long after attach to wait for a non-empty `AttachedAutomator.windows()` list. */
    public val firstWindowMs: Long = DEFAULT_FIRST_WINDOW_MS,
) {
    public companion object {
        public const val DEFAULT_PROCESS_ALIVE_MS: Long = 5_000
        public const val DEFAULT_JVM_ATTACHABLE_MS: Long = 15_000
        public const val DEFAULT_AGENT_BOOTSTRAP_MS: Long = 15_000
        public const val DEFAULT_FIRST_WINDOW_MS: Long = 30_000
    }
}
