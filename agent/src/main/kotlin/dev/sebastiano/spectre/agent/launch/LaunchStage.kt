package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

/**
 * Ordered readiness stages for [LaunchAndAttach.launch].
 *
 * Each stage failing produces a distinct [LaunchException] so callers can distinguish "exited
 * before attach" from "attached but no window" without parsing free text.
 */
@ExperimentalSpectreAgentApi
public enum class LaunchStage {
    /** The launched OS process is still alive after start. */
    PROCESS_ALIVE,

    /** The target JVM is visible to the JDK Attach API (or discovered as a descendant). */
    JVM_ATTACHABLE,

    /** Agent load + bootstrap found `ComposeAutomator` and bound the UDS. */
    AGENT_BOOTSTRAP,

    /** At least one window is visible through the attached automator. */
    FIRST_WINDOW,
}
