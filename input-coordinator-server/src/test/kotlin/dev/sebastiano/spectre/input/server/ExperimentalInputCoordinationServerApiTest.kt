@file:OptIn(dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi::class)

package dev.sebastiano.spectre.input.server

import dev.sebastiano.spectre.input.ExperimentalSpectreInputCoordinationApi
import kotlin.test.Test
import kotlin.test.assertNotNull

class ExperimentalInputCoordinationServerApiTest {

    @Test
    fun `the published server surface remains experimental`() {
        listOf(
                CoordinatorProcessLauncher::class.java,
                CoordinatorProcessMain::class.java,
                LaunchingInputCoordinatorClientProvider::class.java,
                LocalCoordinatorServer::class.java,
            )
            .forEach { type ->
                assertNotNull(
                    type.getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java),
                    "${type.name} must remain experimental until the coordination API graduates",
                )
            }

        val main =
            Class.forName("dev.sebastiano.spectre.input.server.CoordinatorProcessMainKt")
                .getDeclaredMethod("main", Array<String>::class.java)
        assertNotNull(main.getAnnotation(ExperimentalSpectreInputCoordinationApi::class.java))
    }
}
