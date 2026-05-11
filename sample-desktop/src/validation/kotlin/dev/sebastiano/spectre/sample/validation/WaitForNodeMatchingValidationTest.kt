package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * [`ComposeAutomator.waitForNode`][dev.sebastiano.spectre.core.ComposeAutomator.waitForNode]
 * matching modes and timeout behaviour (R4).
 *
 * The argument-validation and EDT-rejection contracts are exercised at the unit level in
 * `WaitForNodeTest`. Those tests do not need a Compose surface; the matching modes do, because the
 * matcher walks live semantics. Living in the validation suite means we run against the real EDT /
 * Compose / SemanticsReader path that production users hit.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitForNodeMatchingValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre R4 waitForNode matching validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `waitForNode resolves by tag only`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val node = waitForNode(tag = "incrementButton")
            assertEquals("incrementButton", node.testTag, "tag-only match should return the button")
        }
    }

    @Test
    fun `waitForNode resolves by text only`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            // Read the button's current 'Count: N' text first so the assertion is robust against
            // any counter state that survived a previous test in this PER_CLASS fixture.
            val button = waitForTestTag("incrementButton")
            val buttonText = button.text
            assertNotNull(buttonText, "incrementButton must expose its 'Count: N' text")

            val node = waitForNode(text = buttonText)
            assertTrue(
                node.texts.any { it == buttonText } || node.editableText == buttonText,
                "text-only match should return a node carrying the requested text, got texts=${node.texts}",
            )
        }
    }

    @Test
    fun `waitForNode combines tag and text with AND semantics`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val buttonText = button.text
            assertNotNull(buttonText, "incrementButton must expose its 'Count: N' text")

            // Positive case: both predicates match the same node.
            val matched = waitForNode(tag = "incrementButton", text = buttonText)
            assertEquals("incrementButton", matched.testTag)
            assertTrue(
                matched.texts.any { it == buttonText } || matched.editableText == buttonText,
                "combined match should carry both the requested tag and text",
            )

            // Negative case: text exists on the button but not under the picker's testTag, so the
            // combined match must time out — AND semantics, not OR.
            val timeoutError =
                runCatching {
                        waitForNode(
                            tag = "scenario.counter",
                            text = buttonText,
                            timeout = 250.milliseconds,
                            pollInterval = 50.milliseconds,
                        )
                    }
                    .exceptionOrNull()
            assertNotNull(
                timeoutError,
                "expected timeout when tag+text refer to disjoint nodes (AND, not OR)",
            )
            Unit
        }
    }

    @Test
    fun `waitForNode throws when no node ever matches within the timeout`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val error =
                runCatching {
                        waitForNode(
                            tag = "this.tag.definitely.does.not.exist",
                            timeout = 250.milliseconds,
                            pollInterval = 50.milliseconds,
                        )
                    }
                    .exceptionOrNull()
            assertNotNull(error, "expected waitForNode to throw on timeout")
            Unit
        }
    }
}
