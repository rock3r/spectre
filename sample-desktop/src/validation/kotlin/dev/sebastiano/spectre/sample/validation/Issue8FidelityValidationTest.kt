package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * End-to-end validation for #8 — runtime fidelity of the in-process automator on a real display.
 *
 * Covers the four areas the spike gist flagged as needing live verification:
 * - HiDPI bounds: `boundsOnScreen` reflects the true on-screen rectangle on Retina (boundsInWindow
 *   ÷ density)
 * - Focus state: `AutomatorNode.isFocused` follows actual focus changes triggered through the
 *   automator
 * - Clipboard `typeText`: the paste-based `typeText` path lands the right characters in the focused
 *   field (synthetic Cmd/Ctrl+V works against Compose text fields)
 * - Scroll bounds drift: a node's `boundsOnScreen` updates after the parent scrolls, so subsequent
 *   click coordinates follow the visible item
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Issue8FidelityValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre #8 validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `HiDPI target bounds reflect real on-screen geometry`() {
        with(fixture.automator) {
            navigateToScenario("scenario.hidpi")
            val target1 = waitForTestTag("hidpi.target.40x0")
            val target2 = waitForTestTag("hidpi.target.200x80")

            val b1 = target1.boundsOnScreen
            val b2 = target2.boundsOnScreen

            // 48dp squares — width and height should match (after density scaling, both dimensions
            // scale by the same factor, so they remain equal in pixel space).
            assertEquals(b1.width, b1.height, "Target 1 should be square")
            assertEquals(b2.width, b2.height, "Target 2 should be square")
            assertEquals(
                b1.width,
                b2.width,
                "Both targets are 48dp — same pixel size at 1x density",
            )

            // The dp-space delta between targets is (160, 80). Multiplied by density that should
            // match the on-screen pixel delta. We don't know density directly here, but we know the
            // ratio (deltaX / deltaY) must equal 160 / 80 = 2.0.
            val deltaX = b2.x - b1.x
            val deltaY = b2.y - b1.y
            assertTrue(deltaX > 0, "Second target must be to the right of the first")
            assertTrue(deltaY > 0, "Second target must be below the first")
            val ratio = deltaX.toDouble() / deltaY.toDouble()
            assertTrue(
                abs(ratio - 2.0) < 0.05,
                "Pixel delta ratio should be 2.0 (160dp / 80dp), was $ratio (Δx=$deltaX Δy=$deltaY)",
            )

            // centerOnScreen must lie inside boundsOnScreen — this is the click-target invariant
            // that drives the entire input pipeline.
            val center = target1.centerOnScreen
            assertTrue(
                center.x in b1.x..(b1.x + b1.width) && center.y in b1.y..(b1.y + b1.height),
                "centerOnScreen $center must lie inside boundsOnScreen $b1",
            )
        }
    }

    @Test
    @Order(2)
    fun `focus jump button transfers isFocused to the second field`() {
        with(fixture.automator) {
            navigateToScenario("scenario.focus")
            val firstField = waitForTestTag("focus.field.first")
            click(firstField)
            // After clicking, the first field should report focus.
            eventually(description = "first field focused after click") {
                if (waitForTestTag("focus.field.first").isFocused) Unit else null
            }

            // Now press the jump button — focus should move to the second field.
            click(waitForTestTag("focus.jumpButton"))
            eventually(description = "second field focused after jump") {
                val second = findOneByTestTag("focus.field.second")
                if (second?.isFocused == true) Unit else null
            }

            // And the first field should no longer be focused.
            assertEquals(
                false,
                waitForTestTag("focus.field.first").isFocused,
                "First field must lose focus after the jump",
            )
        }
    }

    @Test
    @Order(3)
    fun `typeText writes characters into the focused text field`() {
        with(fixture.automator) {
            navigateToScenario("scenario.focus")
            val target = waitForTestTag("focus.field.third")
            click(target)
            eventually(description = "third field focused") {
                if (waitForTestTag("focus.field.third").isFocused) Unit else null
            }

            // RobotDriver.typeText now waits for the OS pasteboard to surface the new contents
            // before pressing Cmd+V and pumps the EDT before restoring the previous clipboard,
            // which collapses the cold-JVM clipboard-vs-paste race that previously flaked this
            // assertion. The eventually() loop also re-issues the type if the field is still
            // focused but the first attempt didn't land — covers the very first cold-JVM call
            // where the pasteboard polling burns a few hundred ms before the first paste hits.
            typeText("spectre")
            val typed =
                eventually(
                    description = "third field reflects typed text",
                    timeout = 15.seconds,
                    pollInterval = 250.milliseconds,
                ) {
                    val node = findOneByTestTag("focus.field.third")
                    if (node?.editableText?.contains("spectre") == true) node
                    else {
                        if (node?.isFocused == true) typeText("spectre")
                        null
                    }
                }
            assertNotNull(typed.editableText, "Field should expose editableText after typing")
            assertTrue(
                typed.editableText!!.contains("spectre"),
                "Field's editableText should contain 'spectre', was '${typed.editableText}'",
            )
        }
    }

    @Test
    @Order(4)
    fun `scrolling shifts boundsOnScreen so click coordinates follow the visible item`() {
        with(fixture.automator) {
            navigateToScenario("scenario.scroll")
            val item0Initial = waitForTestTag("scroll.item.0")
            val initialY = item0Initial.boundsOnScreen.y
            val list = waitForTestTag("scroll.list")

            // Compose Desktop scrollables respond to wheel input, not drag — issue several wheel
            // ticks to push the LazyColumn down past item.0's row height.
            repeat(WHEEL_TICKS_TO_SCROLL) { scrollWheel(list, wheelClicks = 1) }

            // After scrolling, item.0 either moves up (its boundsOnScreen.y decreases) or leaves
            // the viewport entirely (LazyColumn unrenders it). Both prove the live-bounds contract:
            // the snapshot is fresh, not cached at first observation.
            eventually(description = "item.0 shifted up or left the viewport") {
                val current = findOneByTestTag("scroll.item.0")
                when {
                    current == null -> Unit // item unrendered — also valid
                    current.boundsOnScreen.y < initialY -> Unit
                    else -> null
                }
            }
        }
    }

    private companion object {
        const val WHEEL_TICKS_TO_SCROLL: Int = 10
    }
}
