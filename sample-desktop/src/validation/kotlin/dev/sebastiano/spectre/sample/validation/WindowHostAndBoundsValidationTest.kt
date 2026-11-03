package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live coverage for the public host accessors on
 * [`AutomatorWindow`][dev.sebastiano.spectre.core.AutomatorWindow] and for
 * [`AutomatorNode.boundsInAwtWindow`][dev.sebastiano.spectre.core.AutomatorNode.boundsInAwtWindow]
 * (#566). None of this file opts in to `InternalSpectreApi`.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WindowHostAndBoundsValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre window host validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `surface exposes its hosting window and host component`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val surface = tree().windows().firstOrNull { it.surfaceId == button.surfaceId }
            assertNotNull(surface, "no AutomatorWindow for surface ${button.surfaceId}")

            assertSame(surface.composePanel ?: surface.window, surface.hostComponent)
            assertTrue(
                SwingUtilities.isDescendingFrom(surface.hostComponent, surface.window),
                "host component must live inside the hosting window",
            )
        }
    }

    @Test
    fun `boundsInAwtWindow is boundsOnScreen relative to the window origin`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val surface = tree().windows().first { it.surfaceId == button.surfaceId }

            var onScreen = Rectangle()
            var inWindow = Rectangle()
            var windowOrigin = Point()
            var windowSize = Rectangle()
            SwingUtilities.invokeAndWait {
                onScreen = button.boundsOnScreen
                inWindow = button.boundsInAwtWindow
                windowOrigin = surface.window.locationOnScreen
                windowSize = Rectangle(0, 0, surface.window.width, surface.window.height)
            }

            val expected = Rectangle(onScreen).apply { translate(-windowOrigin.x, -windowOrigin.y) }
            assertEquals(expected, inWindow)
            assertTrue(
                windowSize.contains(inWindow),
                "window-relative bounds $inWindow must fit in the window $windowSize",
            )
        }
    }
}
