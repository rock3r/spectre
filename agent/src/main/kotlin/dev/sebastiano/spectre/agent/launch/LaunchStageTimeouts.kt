package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

/**
 * Per-stage timeouts for [LaunchAndAttach.launch] readiness.
 *
 * Defaults favour CI-friendly Compose Desktop fixture boots without sleep-and-pray polling of the
 * whole pipeline as one opaque budget.
 *
 * Direct `java` launches use [DEFAULT_JVM_ATTACHABLE_MS] (15s). Gradle-ish launches that leave
 * [jvmAttachableMs] at that default are expanded via [forGradleishLaunch] to
 * [DEFAULT_GRADLE_JVM_ATTACHABLE_MS] (120s) so cold daemon start + compile can finish before the
 * app JVM appears (#386). Explicit non-default values are never expanded.
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
    /**
     * Effective timeouts for a Gradle-ish launch. When [jvmAttachableMs] is still the direct-launch
     * default, expand it to [DEFAULT_GRADLE_JVM_ATTACHABLE_MS]. Explicit overrides (including short
     * budgets used by failure-classification tests) are returned unchanged.
     */
    public fun forGradleishLaunch(): LaunchStageTimeouts =
        if (jvmAttachableMs == DEFAULT_JVM_ATTACHABLE_MS) {
            copy(jvmAttachableMs = DEFAULT_GRADLE_JVM_ATTACHABLE_MS)
        } else {
            this
        }

    public companion object {
        public const val DEFAULT_PROCESS_ALIVE_MS: Long = 5_000
        public const val DEFAULT_JVM_ATTACHABLE_MS: Long = 15_000
        /**
         * Default JVM_ATTACHABLE budget for Gradle-ish launches that did not set an explicit
         * [jvmAttachableMs]. Matches [LaunchAndAttachGradleIntegrationTest] and covers cold daemon
         * + compile after `gradlew --stop` on Windows headed smoke (#386).
         */
        public const val DEFAULT_GRADLE_JVM_ATTACHABLE_MS: Long = 120_000
        public const val DEFAULT_AGENT_BOOTSTRAP_MS: Long = 15_000
        public const val DEFAULT_FIRST_WINDOW_MS: Long = 30_000
    }
}
