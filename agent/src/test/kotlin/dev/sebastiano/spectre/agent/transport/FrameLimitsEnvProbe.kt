@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.transport

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi

/**
 * Prints the frame budget this JVM resolves, optionally after applying an explicit value.
 *
 * Spawned by [FrameLimitsEnvTest]. Process-start environment combinations require a real child JVM
 * because tests cannot change their own environment reliably.
 */
public fun main(arguments: Array<String>) {
    arguments.singleOrNull()?.let { FrameLimits.configure(FrameLimits.parseMaxFrameBytes(it)!!) }
    println("budget=${FrameLimits.maxFrameBytes}")
}
