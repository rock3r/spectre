package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.testing.runSpectreTest
import java.awt.GraphicsEnvironment
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Live acceptance that [runSpectreTest] preserves real wall-clock time for Spectre-internal `delay`
 * sites when driving the shipped sample UI (synthetic Robot against the real composition).
 *
 * These tests call the public automator entry points (`longClick`, `swipe`, `pasteText`) under the
 * shipped runner — not a reimplemented delay stub.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RunSpectreTestValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre runSpectreTest validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `longClick hold under runSpectreTest consumes real wall time`(): Unit = runSpectreTest {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val hold = 500.milliseconds
            val started = System.nanoTime()
            longClick(button, holdFor = hold)
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            assertTrue(
                elapsedMs >= hold.inWholeMilliseconds,
                "longClick(holdFor=$hold) under runSpectreTest must take ≥" +
                    "${hold.inWholeMilliseconds}ms wall time, was ${elapsedMs}ms",
            )
        }
    }

    @Test
    @Order(2)
    fun `swipe step pacing under runSpectreTest consumes real wall time`(): Unit = runSpectreTest {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val button = waitForTestTag("incrementButton")
            val start = button.centerOnScreen
            // Multi-step swipe over 400ms must not collapse under virtual time.
            val duration = 400.milliseconds
            val started = System.nanoTime()
            swipe(
                startX = start.x,
                startY = start.y,
                endX = start.x + 80,
                endY = start.y,
                steps = 8,
                duration = duration,
            )
            // Allow some autoDelay overhead subtraction, but require a clear multi-hundred-ms
            // floor.
            val minExpectedMs = 250L
            val elapsedMs = (System.nanoTime() - started) / NANOS_PER_MILLI
            assertTrue(
                elapsedMs >= minExpectedMs,
                "swipe(duration=$duration) under runSpectreTest must take ≥${minExpectedMs}ms " +
                    "wall time, was ${elapsedMs}ms",
            )
        }
    }

    @Test
    @Order(3)
    fun `pasteText under runSpectreTest drives the shipped path`(): Unit = runSpectreTest {
        with(fixture.automator) {
            navigateToScenario("scenario.counter")
            val field = waitForTestTag("textInput")
            click(field)
            pasteText("runSpectreTest-paste")
            eventually<AutomatorNode>(description = "echo reflects paste") {
                val echo = findOneByTestTag("echoText")
                if (echo?.text?.contains("runSpectreTest-paste") == true) echo else null
            }
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
