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
 * Live acceptance for [dev.sebastiano.spectre.core.ComposeAutomator.waitForVisualIdle].
 *
 * Covers:
 * - **Cold capture (#356):** the fixture starts in a fresh worker JVM. Navigation and
 *   `waitForIdle()` are semantics-only, so the first `waitForVisualIdle` poll is the first pixel
 *   capture against a static Compose surface.
 * - **Window-scoped sampling (#355):** with `spectre-recording` on the classpath (this module),
 *   frame hashes come from native window capture, not `Robot.createScreenCapture` of a screen
 *   rectangle. A full default `stableFrames = 3` streak must complete against a static UI so
 *   one-shot helper cost cannot make visual idle unreachable by construction.
 *
 * Skips GitHub-hosted Windows: WGC still capture needs an interactive console (same gate as Issue14
 * screenshot warmup).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitForVisualIdleValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre waitForVisualIdle validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        assumeFalse(
            isHostedWindows(),
            "Live Windows Graphics Capture requires an interactive console",
        )
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

    @Test
    fun `window-scoped visual idle reaches a three-frame streak on a static live Compose surface`() =
        runBlocking {
            with(fixture.automator) {
                navigateToScenario("scenario.hidpi")
                waitForTestTag("hidpi.target.40x0")
                waitForIdle()

                // Default stableFrames; longer timeout absorbs cold helper + three warm stills.
                waitForVisualIdle(timeout = 15.seconds, stableFrames = 3)
            }
        }

    private companion object {
        fun isHostedWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) &&
                System.getenv("GITHUB_ACTIONS") == "true"
    }
}
