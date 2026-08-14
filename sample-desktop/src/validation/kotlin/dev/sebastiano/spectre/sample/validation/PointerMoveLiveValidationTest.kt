package dev.sebastiano.spectre.sample.validation

import dev.sebastiano.spectre.core.ComposeAutomator
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Live proof for #433 pointer-move verbs.
 *
 * The sample hover scenario exposes a hover-sensitive target and a park pad. `moveTo` / `moveBy`
 * must enter the target (hover, no click) and leave it again. The smoke harness treats
 * assumption-skips as a hard fail.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class PointerMoveLiveValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre pointer-move live validation")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `moveTo node hovers without clicking`() = runBlocking {
        with(fixture.automator) {
            openHoverScenario()
            waitForTestTag("hoverStatus")
            assertEquals("idle", waitForTestTag("hoverStatus").text)
            assertEquals("clicks:0", waitForTestTag("hoverClicks").text)

            moveTo(waitForTestTag("hoverTarget"))

            eventually(description = "hover target to enter hovered state") {
                val status = findOneByTestTag("hoverStatus") ?: return@eventually null
                if (status.text == "hovered") status else null
            }
            assertEquals("clicks:0", waitForTestTag("hoverClicks").text)
        }
    }

    @Test
    @Order(2)
    fun `moveBy parks the pointer off the target without clicking`() = runBlocking {
        with(fixture.automator) {
            openHoverScenario()
            val target = waitForTestTag("hoverTarget")
            val park = waitForTestTag("hoverPark")
            moveTo(target)
            eventually(description = "hover target to enter hovered state") {
                val status = findOneByTestTag("hoverStatus") ?: return@eventually null
                if (status.text == "hovered") status else null
            }

            val from = target.centerOnScreen
            val to = park.centerOnScreen
            moveBy(deltaX = to.x - from.x, deltaY = to.y - from.y)

            eventually(description = "hover target to return to idle after moveBy") {
                val status = findOneByTestTag("hoverStatus") ?: return@eventually null
                if (status.text == "idle") status else null
            }
            assertEquals("clicks:0", waitForTestTag("hoverClicks").text)
        }
    }

    @Test
    @Order(3)
    fun `moveTo coordinates hover the same target without clicking`() = runBlocking {
        with(fixture.automator) {
            openHoverScenario()
            val park = waitForTestTag("hoverPark")
            moveTo(park)
            eventually(description = "hover target idle before coordinate moveTo") {
                val status = findOneByTestTag("hoverStatus") ?: return@eventually null
                if (status.text == "idle") status else null
            }

            val center = waitForTestTag("hoverTarget").centerOnScreen
            moveTo(x = center.x, y = center.y)

            eventually(description = "hover target to enter hovered state from coordinates") {
                val status = findOneByTestTag("hoverStatus") ?: return@eventually null
                if (status.text == "hovered") status else null
            }
            assertEquals("clicks:0", waitForTestTag("hoverClicks").text)
        }
    }
}

/** Hover is last in the picker LazyColumn, so it is not composed until the list is scrolled. */
private suspend fun ComposeAutomator.openHoverScenario() {
    if (findOneByTestTag("scenario.hover") == null) {
        val picker = waitForTestTag("scenario.picker")
        eventually(description = "picker entry 'scenario.hover' to compose", timeout = 10.seconds) {
            findOneByTestTag("scenario.hover")?.let {
                return@eventually it
            }
            scrollWheel(picker, wheelClicks = 3)
            null
        }
    }
    navigateToScenario("scenario.hover")
}
