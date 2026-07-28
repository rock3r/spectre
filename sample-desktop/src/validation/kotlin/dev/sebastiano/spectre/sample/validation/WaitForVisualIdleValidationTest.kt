package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live acceptance for the cold-capture path in
 * [dev.sebastiano.spectre.core.ComposeAutomator.waitForVisualIdle].
 *
 * The fixture starts in a fresh worker JVM. Navigation and `waitForIdle()` are semantics-only, so
 * `waitForVisualIdle()` below is the first pixel capture a user performs against a static Compose
 * surface. This is the ordering that previously made an otherwise idle UI time out when native
 * screen capture had a one-off startup cost. One stable frame intentionally isolates that cold
 * public-capture path from the separate window-scoped capture fidelity work in #355, which can make
 * repeated synthetic-screen samples differ even when the Compose content is static.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitForVisualIdleValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre waitForVisualIdle validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `first visual-idle capture completes against a static live Compose surface`() =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.hidpi")
                waitForTestTag("hidpi.target.40x0")
                waitForIdle()

                waitForVisualIdle(timeout = 5.seconds, stableFrames = 1)
            }
        }
}
