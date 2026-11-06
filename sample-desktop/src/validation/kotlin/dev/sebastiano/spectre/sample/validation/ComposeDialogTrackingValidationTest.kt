@file:OptIn(InternalSpectreApi::class, ExperimentalComposeUiApi::class)

package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposeDialog
import androidx.compose.ui.awt.ComposeWindow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.rememberDialogState
import dev.sebastiano.spectre.core.AutomatorNode
import dev.sebastiano.spectre.core.ComposeAutomator
import dev.sebastiano.spectre.core.InternalSpectreApi
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Timeout

/**
 * Validation for #547: Compose `DialogWindow`s are hosted by a `ComposeDialog` (a `JDialog`), not a
 * `ComposeWindow` and not a `ComposePanel`. Spectre must still track them and read their semantics,
 * both when the dialog is owned by a `ComposeWindow` (the usual `DialogWindow` case) and when it is
 * a top-level window.
 */
class ComposeDialogTrackingValidationTest {

    private val windowsToDispose = mutableListOf<Window>()

    @BeforeEach
    fun assumeDisplay() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
    }

    @AfterTest
    fun tearDown() {
        onEdt { windowsToDispose.forEach(Window::dispose) }
        windowsToDispose.clear()
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks a DialogWindow opened from a Compose Window`() {
        val window = onEdt {
            ComposeWindow().apply {
                title = "Spectre #547 main window"
                size = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
                setContent {
                    Text("main", Modifier.testTag(MAIN_TAG))
                    DialogWindow(
                        onCloseRequest = {},
                        state = rememberDialogState(size = DIALOG_SIZE),
                        title = "Spectre #547 owned dialog",
                    ) {
                        Text("dialog", Modifier.testTag(DIALOG_TAG))
                    }
                }
                windowsToDispose += this
                isVisible = true
            }
        }

        val automator = ComposeAutomator.inProcess()
        val dialogNode = awaitNode(automator, DIALOG_TAG)
        assertNotNull(
            dialogNode,
            "Expected '$DIALOG_TAG' inside the DialogWindow; tracked: ${automator.surfaceIds()}",
        )
        assertNotNull(automator.findOneByTestTag(MAIN_TAG), "The main window must stay tracked")

        val dialogSurface = automator.windows.single { it.surfaceId == dialogNode.surfaceId }
        assertTrue(
            dialogSurface.window is ComposeDialog,
            "The dialog surface hosts a ComposeDialog",
        )
        assertTrue(dialogSurface.window.owner === window, "The DialogWindow is owned by the window")
        assertTrue(dialogSurface.isPopup, "An owned dialog is reported as a popup surface")
        assertEquals(
            1,
            automator.windows.count { it.window === dialogSurface.window },
            "The dialog must be tracked once, got ${automator.surfaceIds()}",
        )
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks a top-level ComposeDialog`() {
        onEdt {
            // A `Window`-typed null owner makes a truly owner-less dialog, which is what a
            // `DialogWindow` composed directly under `application { }` creates. A `Frame`-typed
            // null would get Swing's hidden shared owner frame instead.
            ComposeDialog(null as Window?).apply {
                title = "Spectre #547 top-level dialog"
                size = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
                setContent { Box(Modifier.size(HOST_CONTENT_DP.dp).testTag(DIALOG_TAG)) }
                windowsToDispose += this
                isVisible = true
            }
        }

        val automator = ComposeAutomator.inProcess()
        val dialogNode = awaitNode(automator, DIALOG_TAG)
        assertNotNull(
            dialogNode,
            "Expected '$DIALOG_TAG' inside the top-level ComposeDialog; " +
                "tracked: ${automator.surfaceIds()}",
        )
        val dialogSurface = automator.windows.single { it.surfaceId == dialogNode.surfaceId }
        assertTrue(dialogSurface.window is ComposeDialog, "The surface hosts a ComposeDialog")
        assertNull(dialogSurface.window.owner, "The dialog must be top-level")
        assertFalse(dialogSurface.isPopup, "A top-level dialog is not a popup surface")
    }

    @Test
    @Timeout(value = TIMEOUT_SECONDS, unit = TimeUnit.SECONDS)
    fun `tracks an OnWindow popup opened from an owned ComposeDialog`() {
        // Same upstream skiko crash as PopupLayerVariantsValidationTest (SKIKO-1132 / spectre#56).
        assumeFalse(
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true),
            "compose.layers.type=WINDOW currently crashes skiko on Windows (SKIKO-1132 / #56)",
        )
        val dialog = withOnWindowLayerType {
            onEdt {
                val owner =
                    ComposeWindow().apply {
                        title = "Spectre #547 popup owner"
                        size = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
                        setContent { Box(Modifier.size(HOST_CONTENT_DP.dp).testTag(MAIN_TAG)) }
                        windowsToDispose += this
                        isVisible = true
                    }
                ComposeDialog(owner).apply {
                    title = "Spectre #547 dialog with popup"
                    size = Dimension(HOST_SIZE_PX, HOST_SIZE_PX)
                    setContent {
                        // Open the popup one frame later: composed during the first composition,
                        // an OnWindow popup fails because the container doesn't know its window.
                        var showPopup by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { showPopup = true }
                        Box(Modifier.size(HOST_CONTENT_DP.dp).testTag(DIALOG_TAG)) {
                            if (showPopup) {
                                Popup { Box(Modifier.size(POPUP_SIZE_DP.dp).testTag(POPUP_TAG)) }
                            }
                        }
                    }
                    windowsToDispose += this
                    isVisible = true
                }
            }
        }

        val automator = ComposeAutomator.inProcess()
        val popupNode = awaitNode(automator, POPUP_TAG)
        assertNotNull(
            popupNode,
            "Expected '$POPUP_TAG' in the dialog's popup; tracked: ${automator.surfaceIds()}",
        )
        val popupSurface = automator.windows.single { it.surfaceId == popupNode.surfaceId }
        assertTrue(popupSurface.window.owner === dialog, "The popup layer is owned by the dialog")
        assertTrue(popupSurface.isPopup, "The overlay layer surface is reported as a popup")
        assertEquals(
            1,
            automator.windows.count { it.window === popupSurface.window },
            "The popup layer window must be tracked once, got ${automator.surfaceIds()}",
        )
        assertNotNull(automator.findOneByTestTag(DIALOG_TAG), "The dialog content stays tracked")
    }

    private fun awaitNode(automator: ComposeAutomator, tag: String): AutomatorNode? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DISCOVERY_TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            automator.refreshWindows()
            automator.findOneByTestTag(tag)?.let {
                return it
            }
            Thread.sleep(POLL_MILLIS)
        }
        return null
    }

    private companion object {
        const val TIMEOUT_SECONDS = 30L
        const val DISCOVERY_TIMEOUT_SECONDS = 10L
        const val POLL_MILLIS = 50L
        const val HOST_SIZE_PX = 320
        const val HOST_CONTENT_DP = 200
        val DIALOG_SIZE = DpSize(240.dp, 160.dp)
        const val MAIN_TAG = "in.main"
        const val DIALOG_TAG = "in.dialog"
        const val POPUP_SIZE_DP = 80
        const val POPUP_TAG = "in.dialog.popup"
    }
}

private fun <T> onEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    var result: Result<T>? = null
    SwingUtilities.invokeAndWait { result = runCatching(block) }
    return checkNotNull(result).getOrThrow()
}
