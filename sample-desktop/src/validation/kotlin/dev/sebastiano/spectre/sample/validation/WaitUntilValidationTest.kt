package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live-Compose coverage for
 * [`ComposeAutomator.waitUntil`][dev.sebastiano.spectre.core.ComposeAutomator.waitUntil]: the
 * predicate-shaped wait scoped to the Spectre-visible semantics tree.
 *
 * The argument-validation, EDT-rejection, and timeout-diagnostic contracts are exercised at the
 * unit level in `WaitUntilTest`. What needs a real Compose surface is what the verb exists for:
 * barriers a tag/text selector cannot express — a node *count*, a *combination* of conditions, the
 * shape of the tracked-window set — evaluated against a tree that every poll re-reads, so a window
 * or popup that appeared between two polls is seen rather than answered from a stale snapshot.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitUntilValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre waitUntil validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `waitUntil waits on a node count, which no tag or text selector can express`(): Unit =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.scroll")
                waitForTestTag("scroll.list")

                waitUntil(description = "the lazy list has realised at least five items") {
                    allNodes().count { it.testTag?.startsWith("scroll.item.") == true } >= 5
                }

                assertTrue(
                    tree().allNodes().count { it.testTag?.startsWith("scroll.item.") == true } >= 5,
                    "waitUntil returned before the item count condition actually held",
                )
            }
        }

    @Test
    fun `waitUntil observes a secondary window entering and leaving the tracked set`(): Unit =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.multiwindow")
                val trackedBefore = tree().windows().size

                // The new surface only becomes visible to the condition because every poll goes
                // through tree(), which refreshes the tracked-window set first.
                click(waitForTestTag("multiwindow.toggleButton"))
                waitUntil(description = "the secondary window joins the tracked set") {
                    windows().size > trackedBefore
                }

                val secondary = waitForTestTag("multiwindow.secondary.text")
                val secondarySurfaceId = secondary.surfaceId

                click(waitForTestTag("multiwindow.secondary.dismissButton"))
                waitUntil(description = "the secondary window leaves the tracked set") {
                    windows().none { window -> window.surfaceId == secondarySurfaceId }
                }
            }
        }

    @Test
    fun `waitUntil waits on a combination of conditions`(): Unit = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.popup")
            click(waitForTestTag("popup.toggleButton"))
            waitForTestTag("popup.body")

            // "Everything the popup contributed is gone AND the trigger is back" is one barrier,
            // not two: waitUntilGone can only express half of it per call.
            click(waitForTestTag("popup.dismissButton"))
            waitUntil(
                description = "the popup is dismissed and its trigger is interactable again"
            ) {
                val tags = allNodes().mapNotNull { it.testTag }.toSet()
                "popup.body" !in tags && "popup.text" !in tags && "popup.toggleButton" in tags
            }
        }
    }

    @Test
    fun `waitUntil names the description and the timeout when the condition never holds`(): Unit =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.counter")
                waitForTestTag("incrementButton")

                val error =
                    assertFailsWith<IllegalStateException> {
                        waitUntil(
                            description = "a second increment button appears",
                            timeout = 250.milliseconds,
                            pollInterval = 50.milliseconds,
                        ) {
                            allNodes().count { it.testTag == "incrementButton" } > 1
                        }
                    }

                val message = error.message.orEmpty()
                assertTrue(
                    message.contains("waitUntil timed out after 250ms"),
                    "expected the timeout in the message, got: $message",
                )
                assertTrue(
                    message.contains("a second increment button appears"),
                    "expected the description in the message, got: $message",
                )
            }
        }
}
