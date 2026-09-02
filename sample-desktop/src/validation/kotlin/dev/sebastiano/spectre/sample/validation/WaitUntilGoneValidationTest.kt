package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live-Compose coverage for
 * [`ComposeAutomator.waitUntilGone`][dev.sebastiano.spectre.core.ComposeAutomator.waitUntilGone]
 * (#438): the wait-for-absence counterpart to `waitForNode`.
 *
 * The argument-validation, EDT-rejection, and timeout-diagnostic contracts are exercised at the
 * unit level in `WaitUntilGoneTest`. What needs a real Compose surface is the thing the verb exists
 * for: a dismissed popup or a closed secondary `Window` takes its whole semantics tree out of the
 * tracked-window set, and only its absence across every tracked window is observable.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitUntilGoneValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre #438 waitUntilGone validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `waitUntilGone returns once a dismissed popup's nodes leave every tracked window`(): Unit =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.popup")
                click(waitForTestTag("popup.toggleButton"))
                waitForTestTag("popup.body")

                click(waitForTestTag("popup.dismissButton"))
                waitUntilGone(tag = "popup.body")

                // The wait refreshes windows before each poll, so the snapshot it returns on is
                // current: nothing under the popup's tag may remain anywhere.
                assertFalse(hasTag("popup.body"), "popup body still present after waitUntilGone")
                assertFalse(hasTag("popup.text"), "popup text still present after waitUntilGone")
            }
        }

    @Test
    fun `waitUntilGone returns once a closed secondary Window leaves the tracked set`(): Unit =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.multiwindow")
                click(waitForTestTag("multiwindow.toggleButton"))
                val secondaryText = waitForTestTag("multiwindow.secondary.text")
                val secondarySurfaceId = secondaryText.surfaceId

                click(waitForTestTag("multiwindow.secondary.dismissButton"))
                waitUntilGone(tag = "multiwindow.secondary.text")

                assertFalse(
                    hasTag("multiwindow.secondary.text"),
                    "secondary window text still present after waitUntilGone",
                )
                eventually(description = "surface $secondarySurfaceId to stop being tracked") {
                    if (secondarySurfaceId !in surfaceIds()) Unit else null
                }
            }
        }

    @Test
    fun `waitUntilGone matches by text`(): Unit = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.popup")
            click(waitForTestTag("popup.toggleButton"))
            val popupText = waitForTestTag("popup.text").text
            assertNotNull(popupText, "popup.text must expose its text")

            click(waitForTestTag("popup.dismissButton"))
            waitUntilGone(text = popupText)

            assertFalse(hasText(popupText), "popup text still present after waitUntilGone")
        }
    }

    @Test
    fun `waitUntilGone names the selector and timeout when the node stays on screen`(): Unit =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.counter")
                waitForTestTag("incrementButton")

                val error =
                    assertFailsWith<IllegalStateException> {
                        waitUntilGone(
                            tag = "incrementButton",
                            timeout = 250.milliseconds,
                            pollInterval = 50.milliseconds,
                        )
                    }

                val message = error.message.orEmpty()
                assertTrue(
                    message.contains("waitUntilGone timed out after 250ms"),
                    "expected the timeout in the message, got: $message",
                )
                assertTrue(
                    message.contains("tag=\"incrementButton\""),
                    "expected the selector in the message, got: $message",
                )
            }
        }
}
