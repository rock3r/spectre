package dev.sebastiano.spectre.sample.validation

import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder

/**
 * Closes the remaining v1 checklist items on #7 that don't depend on the IntelliJ/Jewel host:
 * - same-window extra semantics roots (Compose + embedded SwingPanel) are surfaced
 * - JDialog-hosted `ComposePanel` content is discoverable via the automator
 *
 * Popup-layer-variant coverage (`OnSameCanvas`/`OnComponent`/`OnWindow`) lives in
 * [`PopupLayerVariantsValidationTest`] because it requires the JVM-startup `-Dcompose.layers.type=`
 * flag and runs through dedicated Gradle subtasks.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class Issue7CompletionValidationTest {

    private val fixture = SampleAppFixture(title = "Spectre #7 completion")

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        fixture.start()
    }

    @AfterAll fun stop() = fixture.stop()

    @Test
    @Order(1)
    fun `dual-panel scenario surfaces both Compose roots`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.dualpanel")
            // Both nodes live in the same Window but the SwingPanel host installs an extra
            // semantics root. The assertion is "both reachable via the same automator query"
            // — we don't care which root owns each, only that the automator surfaces them.
            val left = waitForTestTag("dual.left.text")
            val swingHost = waitForTestTag("dual.right.swingHost")
            assertNotNull(left.text, "Left Compose pane should expose its text")
            // The SwingPanel itself shows up as a Compose node (the bridge composable owns the
            // testTag) — proves the automator walks past the bridge into the Compose tree.
            assertNotNull(swingHost, "SwingPanel host node should be discoverable")
        }
    }

    @Test
    @Order(2)
    fun `JDialog hosting ComposePanel exposes its tree to the automator`() = runBlocking {
        with(fixture.automator) {
            navigateToScenario("scenario.jdialog")
            val initialWindows = windows.size
            click(waitForTestTag("jdialog.toggleButton"))
            // The JDialog enters the AWT hierarchy a frame or two after the Swing call returns,
            // and the Compose semantics owner inside its embedded ComposePanel attaches
            // asynchronously. Wait until both the window count grew AND the body resolves.
            eventually(description = "jdialog window registered + body discoverable") {
                if (windows.size > initialWindows && findOneByTestTag("jdialog.body") != null) Unit
                else null
            }
            val body = waitForTestTag("jdialog.body")
            assertNotNull(body.text, "JDialog body text should be readable through ComposePanel")
            // Tear down — the dialog's DisposableEffect closes it once the toggle goes false.
            click(waitForTestTag("jdialog.dismissButton"))
            // Wait for the body to disappear from the semantics tree — the JDialog frame may take
            // a beat longer to fully dispose (Swing posts the dispose via invokeLater and macOS
            // can leave the AWT peer around briefly), so we only assert on the Compose-level
            // discoverability, not the raw windows count.
            waitUntilGone("jdialog.body")
        }
    }
}
