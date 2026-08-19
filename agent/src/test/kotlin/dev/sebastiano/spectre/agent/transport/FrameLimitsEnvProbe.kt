@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

/**
 * Prints the frame budget this JVM resolved at class-initialization time.
 *
 * Spawned by [FrameLimitsEnvTest]. The environment is only read inside `FrameLimits`' own
 * initializer, so the override path can only be exercised in a process that started with the
 * variable already set — an in-process test always finds the class initialized.
 */
public fun main() {
    println("budget=${FrameLimits.maxFrameBytes}")
}
