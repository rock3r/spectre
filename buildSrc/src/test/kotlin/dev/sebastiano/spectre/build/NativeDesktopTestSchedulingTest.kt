package dev.sebastiano.spectre.build

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeDesktopTestSchedulingTest {
    @Test
    fun `native desktop suites take the build-wide lease`() {
        listOf(
                ":core:test",
                ":agent:test",
                ":cli:test",
                ":recording:test",
                ":sample-desktop:validationTest",
                ":sample-desktop:validationTestLayerWindow",
                ":sample-desktop:headedRobotContentionTest",
                ":sample-intellij-plugin:uiTest",
                ":sample-intellij-plugin:stockInjectUiTest",
            )
            .forEach { assertTrue(it in nativeDesktopTestTaskPaths, "$it needs the desktop lease") }
    }

    @Test
    fun `headless and ordinary test tasks stay independently schedulable`() {
        listOf(
                ":input-coordinator:test",
                ":input-coordinator-server:test",
                ":server:test",
                ":testing:test",
                ":agent-runtime:test",
                ":agent-test-fixture:test",
                ":sample-desktop:test",
            )
            .forEach { assertFalse(it in nativeDesktopTestTaskPaths, "$it must remain parallel") }
    }
}
