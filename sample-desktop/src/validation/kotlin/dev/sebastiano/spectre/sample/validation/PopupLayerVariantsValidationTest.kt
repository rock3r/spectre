package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
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
        // Issue #23 finding: on Windows + JBR 21.0.9, `compose.layers.type=WINDOW` triggers a
        // fatal native crash in skiko-windows-x64.dll during the first `SkiaLayer.update`
        // (PictureRecorder.finishRecordingAsPicture). The crash kills the worker JVM before
        // the test can run, so Gradle reports the suite as a non-informative `<skipped/>`
        // with the test executor's "Could not write 127.0.0.1:NNNN" error.
        //
        // Tracking:
        //   - Spectre side: https://github.com/rock3r/spectre/issues/56
        //   - Upstream: https://youtrack.jetbrains.com/issue/SKIKO-1132
        //
        // Until JBR / skiko ships a fix, gate the Window-mode variant out of the Windows runner
        // cleanly so the suite reports the actual reason and the rest of the test class still
        // validates the SameCanvas + Component layer modes Windows users can actually use today.
        // Drop this assumeFalse when SKIKO-1132 reports a fix in a JBR / Compose Multiplatform
        // release we've bumped to.
        val isOnWindowLayer = layerType.equals("WINDOW", ignoreCase = true)
        val isWindowsHost =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
        assumeFalse(
            isOnWindowLayer && isWindowsHost,
            "compose.layers.type=WINDOW currently crashes JBR's skiko-windows-x64.dll on " +
                "Windows (SKIKO-1132 / spectre#56); SameCanvas + Component modes work and cover " +
                "the same WindowTracker discovery paths.",
        )
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    fun `popup body resolves under the active layer type`() = runBlocking {
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
