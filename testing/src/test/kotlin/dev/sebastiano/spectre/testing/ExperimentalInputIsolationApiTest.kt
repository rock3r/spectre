@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.testing

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import kotlin.test.Test
import kotlin.test.assertNotNull

class ExperimentalInputIsolationApiTest {

    @Test
    fun `JUnit input isolation remains experimental`() {
        listOf(InputIsolationConfig::class.java, InputIsolationMode::class.java).forEach { type ->
            assertNotNull(
                type.getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java),
                "${type.name} must remain experimental until the coordination API graduates",
            )
        }

        listOf(ComposeAutomatorExtension::class.java, ComposeAutomatorRule::class.java).forEach {
            type ->
            listOf(
                    type.getConstructor(InputIsolationConfig::class.java),
                    type.getConstructor(
                        FailureArtifactsConfig::class.java,
                        FailureVideoConfig::class.java,
                        InputIsolationConfig::class.java,
                        Function0::class.java,
                    ),
                )
                .forEach { constructor ->
                    assertNotNull(
                        constructor.getAnnotation(
                            ExperimentalSpectreInputCoordinationApi::class.java
                        )
                    )
                }
        }
    }
}
