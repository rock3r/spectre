package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttachRuntimePreflightTest {
    @Test
    fun `explains how to enable dynamic agent loading on the target JVM`() {
        assertEquals(
            "The target JVM does not allow dynamic agent loading. Restart it with " +
                "`-XX:+EnableDynamicAgentLoading` and retry the attach.",
            dynamicAgentLoadingGuidance(
                "Failed to load agent library: Dynamic agent loading is not enabled. " +
                    "Use -XX:+EnableDynamicAgentLoading to launch target VM."
            ),
        )
    }

    @Test
    fun `rejects JVMs older than Java 21 with actionable guidance`() {
        val exception =
            assertFailsWith<JavaVersionUnsupportedException> {
                AttachRuntimePreflight.requireSupported(javaFeature = 20)
            }

        assertTrue(exception.message.orEmpty().contains("JDK 21"))
    }
}
