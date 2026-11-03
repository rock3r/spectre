package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import dev.sebastiano.spectre.core.RobotDriver
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import java.awt.Window
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import javax.swing.JFrame
import javax.swing.JTextField
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Timeout

/**
 * Synthetic input in Swing hosts that embed `ComposePanel`s next to Swing components (#565), the
 * IntelliJ / Jewel shape where `JewelComposePanelWrapper` sits beside plain Swing fields.
 *
 * Builds its own frames instead of using [SampleAppFixture]: the scenarios need several top-level
 * windows and windows that never take AWT focus.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class HybridSwingComposeSyntheticInputValidationTest {

    private val windows = CopyOnWriteArrayList<Window>()

    @AfterEach
    fun disposeWindows() {
        for (window in windows) SwingUtilities.invokeLater { window.dispose() }
        windows.clear()
    }

    @Test
    fun `clicking a Compose field moves AWT focus away from a focused Swing field`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        val composeText = mutableStateOf("")
        val swingField = JTextField().apply { preferredSize = Dimension(FIELD_WIDTH, 0) }
        val panel = composePanel { TextField(composeText, requestFocus = false) }
        val frame = showFrame(x = FRAME_X, panel = panel, west = swingField)
        SwingUtilities.invokeAndWait {
            frame.toFront()
            swingField.requestFocusInWindow()
        }
        assumeTrue(
            awaitCondition { frame.focusOwner === swingField },
            "the display cannot give AWT focus to the Swing field, so there is nothing to move",
        )

        val driver = RobotDriver.synthetic(frame)
        val (x, y) = centerOnScreen(panel)
        runBlocking {
            driver.click(x, y)
            driver.typeText("abc")
        }

        awaitCondition { composeText.value == "abc" }
        assertEquals("abc", composeText.value, "keys must follow the click into Compose")
        assertEquals("", swingField.text, "the previously focused Swing field must get no keys")
    }

    @Test
    fun `keys reach the ComposePanel that holds Compose focus rather than the newest window`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        val focusedText = mutableStateOf("")
        val focused = mutableStateOf(false)
        val otherText = mutableStateOf("")
        showFrame(
            x = FRAME_X,
            panel =
                composePanel {
                    TextField(
                        focusedText,
                        requestFocus = true,
                        onFocusChanged = { focused.value = it },
                    )
                },
            focusable = false,
        )
        // Shown later, so a naive "last window first" lookup picks this one.
        showFrame(
            x = SECOND_FRAME_X,
            panel = composePanel { TextField(otherText, requestFocus = false) },
            focusable = false,
        )
        check(awaitCondition { focused.value }) { "the Compose field never took Compose focus" }

        runBlocking { RobotDriver.synthetic().typeText("abc") }

        awaitCondition { focusedText.value == "abc" }
        assertEquals("abc", focusedText.value, "keys must reach the focused Compose field")
        assertEquals("", otherText.value, "the unfocused window must get no keys")
    }

    private fun composePanel(content: @Composable () -> Unit): ComposePanel =
        ComposePanel().apply { setContent(content) }

    private fun showFrame(
        x: Int,
        panel: ComposePanel,
        west: Component? = null,
        focusable: Boolean = true,
    ): JFrame {
        var frame: JFrame? = null
        SwingUtilities.invokeAndWait {
            frame =
                JFrame("HybridSwingComposeSyntheticInputValidationTest").apply {
                    isUndecorated = true
                    focusableWindowState = focusable
                    contentPane.layout = BorderLayout()
                    west?.let { contentPane.add(it, BorderLayout.WEST) }
                    contentPane.add(panel, BorderLayout.CENTER)
                    size = Dimension(FRAME_WIDTH, FRAME_HEIGHT)
                    setLocation(x, FRAME_Y)
                    isVisible = true
                }
        }
        windows += frame!!
        check(awaitCondition { panel.isShowing }) { "frame never showed" }
        return frame!!
    }

    private fun centerOnScreen(component: Component): Pair<Int, Int> {
        var center = 0 to 0
        SwingUtilities.invokeAndWait {
            val origin = component.locationOnScreen
            center = (origin.x + component.width / 2) to (origin.y + component.height / 2)
        }
        return center
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS)
        while (System.nanoTime() < deadline) {
            var met = false
            SwingUtilities.invokeAndWait { met = condition() }
            if (met) return true
            Thread.sleep(POLL_INTERVAL_MS)
        }
        return false
    }

    private companion object {
        const val FRAME_X = 120
        const val SECOND_FRAME_X = 520
        const val FRAME_Y = 120
        const val FRAME_WIDTH = 360
        const val FRAME_HEIGHT = 120
        const val FIELD_WIDTH = 150
        const val WAIT_SECONDS = 10L
        const val POLL_INTERVAL_MS = 25L
    }
}

/** A full-size text field whose text lives in [text], so the test can read it off the EDT. */
@Composable
private fun TextField(
    text: MutableState<String>,
    requestFocus: Boolean,
    modifier: Modifier = Modifier,
    onFocusChanged: (Boolean) -> Unit = {},
) {
    val focusRequester = remember { FocusRequester() }
    BasicTextField(
        value = text.value,
        onValueChange = { text.value = it },
        modifier =
            modifier.fillMaxSize().focusRequester(focusRequester).onFocusChanged {
                onFocusChanged(it.isFocused)
            },
    )
    if (requestFocus) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }
}
