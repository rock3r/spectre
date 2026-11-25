package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 * GitHub-hosted Windows intentionally opts into desktop-region sampling because WGC still capture
 * needs an interactive console. A separate assertion verifies that the default remains fail-closed.
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

                waitForVisualIdle(
                    timeout = 5.seconds,
                    stableFrames = 1,
                    allowScreenRegionFallback = isHostedWindows(),
                )
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
                waitForVisualIdle(
                    timeout = 15.seconds,
                    stableFrames = 3,
                    allowScreenRegionFallback = isHostedWindows(),
                )
            }
        }

    @Test
    fun `hosted Windows fails closed before sampling a nonempty surface`() = runBlocking {
        assumeTrue(isHostedWindows(), "Exercises the hosted Windows capture-policy gate")
        with(fixture.automator) {
            navigateToScenario("scenario.hidpi")
            waitForTestTag("hidpi.target.40x0")
            waitForIdle()

            val error =
                kotlin.test.assertFailsWith<UnsupportedOperationException> {
                    waitForVisualIdle(timeout = 15.seconds, stableFrames = 1)
                }
            kotlin.test.assertTrue(
                error.message.orEmpty().contains("non-interactive GitHub-hosted Windows runner"),
                error.message,
            )
        }
    }

    /**
     * #612: a focused text field blinks its caret about every 500 ms. Three stable samples must fit
     * inside one blink phase, so one window still must take well under 250 ms. Windows only: the
     * one-shot Windows helper took about 400 ms per still, so the streak never completed there.
     */
    @Test
    fun `visual idle settles while a focused text field caret blinks`() = runBlocking {
        assumeTrue(isWindows(), "Measures the Windows Graphics Capture still latency")
        with(fixture.automator) {
            navigateToScenario("scenario.hidpi")
            waitForTestTag("hidpi.target.40x0")
            waitForIdle()
            // Warm the native capture path once, so the measurement below is steady state.
            waitForVisualIdle(
                timeout = 15.seconds,
                stableFrames = 1,
                allowScreenRegionFallback = isHostedWindows(),
            )

            // Sample period on a static surface: every sample matches, so the wait takes about
            // `samples` sample periods. Printed for the #612 before/after numbers.
            val samples = 10
            val staticStart = TimeSource.Monotonic.markNow()
            waitForVisualIdle(
                timeout = 15.seconds,
                stableFrames = samples,
                allowScreenRegionFallback = isHostedWindows(),
            )
            println(
                "visual-idle sample period: " +
                    "${staticStart.elapsedNow().inWholeMilliseconds / samples} ms"
            )

            navigateToScenario("scenario.focus")
            click(waitForTestTag("focus.field.first"))
            eventually(description = "first field focused after click") {
                if (waitForTestTag("focus.field.first").isFocused) Unit else null
            }
            waitForIdle()
            waitForVisualIdle(
                timeout = 5.seconds,
                stableFrames = 3,
                allowScreenRegionFallback = isHostedWindows(),
            )
        }
    }

    private companion object {
        fun isWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    }
}

class HostedWindowsVisualIdlePolicyTest {
    @Test
    fun `self-hosted Windows Actions job uses native capture`() {
        kotlin.test.assertFalse(
            isHostedWindows(
                osName = "Windows 11",
                getenv = {
                    when (it) {
                        "GITHUB_ACTIONS" -> "true"
                        "RUNNER_ENVIRONMENT" -> "self-hosted"
                        else -> null
                    }
                },
            )
        )
    }

    @Test
    fun `GitHub-hosted Windows job opts into screen-region fallback`() {
        kotlin.test.assertTrue(
            isHostedWindows(
                osName = "Windows 11",
                getenv = { if (it == "RUNNER_ENVIRONMENT") "github-hosted" else null },
            )
        )
    }
}

private fun isHostedWindows(
    osName: String = System.getProperty("os.name").orEmpty(),
    getenv: (String) -> String? = System::getenv,
): Boolean =
    osName.startsWith("Windows", ignoreCase = true) &&
        getenv("RUNNER_ENVIRONMENT") == "github-hosted"
