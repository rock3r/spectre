package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Validates the three public APIs introduced by R1 to replace what used to leak through
 * [dev.sebastiano.spectre.core.AutomatorNode]'s `semanticsNode` / `trackedWindow`:
 *
 * - `AutomatorNode.surfaceId`: stable identifier without reaching into the tracked window.
 * - `ComposeAutomator.focusWindow(node)`: raises/focuses the AWT window hosting the node.
 * - `ComposeAutomator.performSemanticsClick(node)`: invokes the Compose `OnClick` action without
 *   going through the OS input stack, for headless contexts where Robot input is unavailable.
 *
 * These tests use the live sample fixture so the assertions exercise the real EDT/Compose machinery
 * the production code paths run through.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class NewInteractionsValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre R1 new-interactions validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `surfaceId on a node matches its window's surfaceId`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val mainSurfaces = surfaceIds()
            assertTrue(
                button.surfaceId in mainSurfaces,
                "node.surfaceId=${button.surfaceId} should appear in surfaceIds()=$mainSurfaces",
            )
        }
    }

    @Test
    @Order(2)
    fun `performSemanticsClick increments the counter without OS input`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            // Capture the count before; the button's text is "Count: N".
            val initialText = button.text
            assertNotNull(initialText, "incrementButton must expose its 'Count: N' text")
            performSemanticsClick(button)
            eventually(description = "counter text to advance after performSemanticsClick") {
                val refreshed = findOneByTestTag("incrementButton") ?: return@eventually null
                if (refreshed.text != initialText) refreshed else null
            }
        }
    }

    @Test
    @Order(3)
    fun `performSemanticsClick fails clearly when node has no OnClick action`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            // The echoText node only appears after typing; pick a static node that has no
            // OnClick — the scenario.title heading is a Text composable with no click action.
            val title = waitForTestTag("scenario.title")
            val error = runCatching { performSemanticsClick(title) }.exceptionOrNull()
            assertNotNull(error, "expected performSemanticsClick(title) to throw")
            assertTrue(
                error.message?.contains("OnClick") == true,
                "expected error to mention OnClick, was: ${error.message}",
            )
        }
    }

    @Test
    @Order(4)
    fun `focusWindow brings the sample window forward without throwing`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            // Smoke: focusWindow should complete without throwing. We cannot reliably assert
            // OS-level window focus state in CI (window managers vary), so the assertion is
            // limited to the contract — the call returns and the node is still discoverable
            // afterwards.
            focusWindow(button)
            assertNotNull(findOneByTestTag("incrementButton"))
        }
    }
}
