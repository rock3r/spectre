@file:OptIn(ExperimentalSpectreAgentApi::class)

package dev.sebastiano.spectre.agent.launch

import dev.sebastiano.spectre.agent.ExperimentalSpectreAgentApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * #386: Gradle-ish launches need a longer JVM_ATTACHABLE budget than direct `java` (cold daemon +
 * compile routinely exceed 15s while the client stays alive). Explicit overrides must stay honoured
 * so short-timeout tests keep classifying failures quickly.
 */
class LaunchStageTimeoutsTest {

    @Test
    fun `gradleish with default jvmAttachable expands to gradle default`() {
        val defaults = LaunchStageTimeouts()
        val effective = defaults.forGradleishLaunch()
        assertEquals(
            LaunchStageTimeouts.DEFAULT_GRADLE_JVM_ATTACHABLE_MS,
            effective.jvmAttachableMs,
        )
        assertEquals(defaults.processAliveMs, effective.processAliveMs)
        assertEquals(defaults.agentBootstrapMs, effective.agentBootstrapMs)
        assertEquals(defaults.firstWindowMs, effective.firstWindowMs)
    }

    @Test
    fun `gradleish with explicit jvmAttachable is not expanded`() {
        val short = LaunchStageTimeouts(jvmAttachableMs = 2_000)
        val effective = short.forGradleishLaunch()
        assertEquals(2_000, effective.jvmAttachableMs)
        assertSame(short, effective)
    }

    @Test
    fun `gradleish with explicit 120s stays 120s`() {
        val long = LaunchStageTimeouts(jvmAttachableMs = 120_000)
        val effective = long.forGradleishLaunch()
        assertEquals(120_000, effective.jvmAttachableMs)
        // Already at the gradle budget — no need to copy when value differs from default 15s.
        assertSame(long, effective)
    }

    @Test
    fun `direct launch path leaves defaults unchanged via no gradle expansion`() {
        val defaults = LaunchStageTimeouts()
        assertEquals(LaunchStageTimeouts.DEFAULT_JVM_ATTACHABLE_MS, defaults.jvmAttachableMs)
    }
}
