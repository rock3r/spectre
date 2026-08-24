package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live-Compose coverage for
 * [`ComposeAutomator.hasTag`][dev.sebastiano.spectre.core.ComposeAutomator.hasTag] /
 * [`ComposeAutomator.hasText`][dev.sebastiano.spectre.core.ComposeAutomator.hasText].
 *
 * Empty-snapshot equivalence with the finders is covered in `HasTagHasTextTest`. Presence against a
 * real semantics tree has to walk live Compose, so it lives here.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HasTagHasTextValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre hasTag/hasText validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `hasTag matches findByTestTag presence on a live counter`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            waitForTestTag("incrementButton")

            assertTrue(hasTag("incrementButton"), "expected incrementButton to be present")
            assertEquals(findByTestTag("incrementButton").isNotEmpty(), hasTag("incrementButton"))
            assertFalse(hasTag("this.tag.definitely.does.not.exist"))
            assertEquals(
                findByTestTag("this.tag.definitely.does.not.exist").isNotEmpty(),
                hasTag("this.tag.definitely.does.not.exist"),
            )
        }
    }

    @Test
    fun `hasText matches findByText presence on a live counter`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val buttonText = button.text
            assertNotNull(buttonText, "incrementButton must expose its 'Count: N' text")

            assertTrue(hasText(buttonText), "expected visible button text to be present")
            assertEquals(findByText(buttonText).isNotEmpty(), hasText(buttonText))
            assertFalse(hasText("this text definitely does not exist"))
            assertEquals(
                findByText("this text definitely does not exist").isNotEmpty(),
                hasText("this text definitely does not exist"),
            )
        }
    }
}
