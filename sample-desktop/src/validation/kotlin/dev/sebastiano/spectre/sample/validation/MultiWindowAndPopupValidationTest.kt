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
 * End-to-end validation for #7 — multi-window and popup support.
 *
 * Drives the live sample app via Spectre's `ComposeAutomator` (with the synthetic input driver) and
 * asserts on what the automator observes. There is no mocking — Spectre is the surface under test,
 * the sample is the workload.
 *
 * Tests run in order so navigation state from a previous test does not leak into the next: each
 * test brings the harness back to its own scenario via [navigateToScenario].
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class MultiWindowAndPopupValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre #7 validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `popup body is discoverable when popup is open`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.popup")
            val toggleButton = waitForTestTag("popup.toggleButton")
            click(toggleButton)
            val popupBody = waitForTestTag("popup.body")
            assertNotNull(popupBody, "Popup body should be discovered after the toggle click")
            // The popup's Text node should also be reachable — proves the popup's full
            // semantics tree is exposed, not just the root container.
            assertNotNull(findOneByTestTag("popup.text"), "Popup text node should be discovered")
            // Tear down for the next test.
            click(waitForTestTag("popup.dismissButton"))
            waitUntilGone("popup.body")
        }
    }

    @Test
    @Order(2)
    fun `popup nodes disappear after dismiss`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.popup")
            click(waitForTestTag("popup.toggleButton"))
            waitForTestTag("popup.body")
            click(waitForTestTag("popup.dismissButton"))
            waitUntilGone("popup.body")
        }
    }

    @Test
    @Order(3)
    fun `secondary Window appears in tracked windows when opened`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.multiwindow")
            // Capture the main window's surfaceIds BEFORE opening the secondary — the order
            // returned by `windows` (built from `Window.getWindows()`) is not contractually
            // guaranteed, so taking `windows.first()` after the open could pick the secondary.
            val mainSurfaceIds = windows.map { it.surfaceId }.toSet()
            val initialCount = windows.size
            click(waitForTestTag("multiwindow.toggleButton"))
            eventually(description = "windows.size > $initialCount") {
                if (windows.size > initialCount) Unit else null
            }
            // Find a node specific to the secondary window — proves we can introspect it.
            val secondaryText = waitForTestTag("multiwindow.secondary.text")
            assertNotNull(secondaryText)
            assertTrue(
                secondaryText.trackedWindow.surfaceId !in mainSurfaceIds,
                "Secondary window's surfaceId should be new, was ${secondaryText.trackedWindow.surfaceId} (mains: $mainSurfaceIds)",
            )
            // Tear down: dismiss the secondary window and wait for the count to drop.
            click(waitForTestTag("multiwindow.secondary.dismissButton"))
            eventually(description = "windows.size back to $initialCount") {
                if (windows.size == initialCount) Unit else null
            }
        }
    }
}
