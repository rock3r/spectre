@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.core

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import java.awt.Robot
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ExperimentalInputCoordinationApiTest {

    @Test
    fun `core coordination types and entry points remain experimental`() {
        listOf(
                ContendedEdtInputLeaseException::class.java,
                ExclusiveInputScope::class.java,
                InputCapabilities::class.java,
                InputLeaseOptions::class.java,
                InputLeasePolicy::class.java,
            )
            .forEach { type ->
                assertNotNull(
                    type.getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java),
                    "${type.name} must remain experimental until the coordination API graduates",
                )
            }

        assertNotNull(
            ComposeAutomator::class
                .java
                .methods
                .single { it.name == "withExclusiveInput" }
                .getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java)
        )

        val composeSource =
            Path.of("src/main/kotlin/dev/sebastiano/spectre/core/ComposeAutomator.kt")
                .readNormalizedText()
        val robotSource =
            Path.of("src/main/kotlin/dev/sebastiano/spectre/core/RobotDriver.kt")
                .readNormalizedText()
        val scopeSource =
            Path.of("src/main/kotlin/dev/sebastiano/spectre/core/ExclusiveInputScope.kt")
                .readNormalizedText()
        assertTrue(
            composeSource.contains(
                "@ExperimentalSpectreInputCoordinationApi\n    public val inputCapabilities"
            )
        )
        assertTrue(
            robotSource.contains(
                "@ExperimentalSpectreInputCoordinationApi\n    public val inputCapabilities"
            )
        )
        assertTrue(scopeSource.contains("automator.checkpointInputLease()"))

        listOf(
                RobotDriver::class.java.getConstructor(InputLeasePolicy::class.java),
                RobotDriver::class
                    .java
                    .getConstructor(Robot::class.java, InputLeasePolicy::class.java),
            )
            .forEach { constructor ->
                assertNotNull(
                    constructor.getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java)
                )
            }
    }
}

private fun Path.readNormalizedText(): String = readText().replace("\r\n", "\n")
