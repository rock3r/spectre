package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Closes #7's popup-layer-variant checklist by exercising the same popup discovery flow under each
 * value of Compose Desktop's `compose.layers.type` JVM-startup property:
 * - unset / `SAME_CANVAS` → popup renders in the same Skia canvas as its parent (default).
 * - `COMPONENT` → popup renders in a sibling AWT `Component` inside the same `Window`.
 * - `WINDOW` → popup renders in its own top-level OS window owned by the parent.
 *
 * The property is consulted by Compose at composition init, so it cannot be flipped per-test — each
 * variant gets its own JVM via the dedicated `validationTestLayer*` Gradle tasks. This test itself
 * is shape-identical across all three modes; the variant only changes how Compose lays out the
 * popup under the hood. Spectre's contract is that the popup body resolves regardless of mode.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PopupLayerVariantsValidationTest {

    private val layerType: String =
        System.getProperty("compose.layers.type", "").ifBlank { "SAME_CANVAS" }
    private val fixture = SampleAppFixture(title = "Spectre #7 popup layer ($layerType)")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `popup body resolves under the active layer type`() {
        with(fixture.automator) {
            navigateToScenario("scenario.popup")
            click(waitForTestTag("popup.toggleButton"))
            val body = waitForTestTag("popup.body")
            assertNotNull(
                body,
                "Popup body should be discoverable under compose.layers.type=$layerType",
            )
            assertNotNull(
                findOneByTestTag("popup.text"),
                "Popup text node should be discoverable under compose.layers.type=$layerType",
            )
            // Tear down so the next class's fixture starts clean.
            click(waitForTestTag("popup.dismissButton"))
            waitUntilGone("popup.body")
        }
    }
}
