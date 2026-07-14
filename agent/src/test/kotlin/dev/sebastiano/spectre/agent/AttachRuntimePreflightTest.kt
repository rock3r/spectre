package dev.sebastiano.spectre.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AttachRuntimePreflightTest {
    @Test
    fun `rejects JVMs older than Java 21 with actionable guidance`() {
        val exception =
            assertFailsWith<JavaVersionUnsupportedException> {
                AttachRuntimePreflight.requireSupported(javaFeature = 20)
            }

        assertTrue(exception.message.orEmpty().contains("JDK 21"))
    }
}
