package dev.sebastiano.spectre.build

import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters

/**
 * The build-wide lease used only by Test tasks which can own windows on the user's desktop.
 *
 * This is deliberately not a convention for every [org.gradle.api.tasks.testing.Test] task:
 * most of the build is headless and should retain Gradle's inter-project parallelism.
 */
abstract class NativeDesktopTestLease : BuildService<BuildServiceParameters.None>

/** Exact task paths whose test workers can open or drive native desktop windows. */
val nativeDesktopTestTaskPaths: Set<String> =
    setOf(
        // These three suites contain live Compose/AWT fixtures (including child JVM fixtures).
        ":core:test",
        ":agent:test",
        ":cli:test",
        // Opt-in native capture smoke tests share the real display with the suites above.
        ":recording:test",
        // Sample validation tasks each launch the Compose Desktop sample.
        ":sample-desktop:headedRobotContentionTest",
        ":sample-desktop:validationTest",
        ":sample-desktop:validationTestLayerComponent",
        ":sample-desktop:validationTestLayerSameCanvas",
        ":sample-desktop:validationTestLayerWindow",
        // IDE Starter launches a real IntelliJ window in a child process.
        ":sample-intellij-plugin:stockInjectUiTest",
        ":sample-intellij-plugin:uiTest",
    )
