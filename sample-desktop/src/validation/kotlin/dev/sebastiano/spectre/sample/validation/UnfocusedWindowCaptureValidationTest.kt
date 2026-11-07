@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.isWaylandSession
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live acceptance for native capture of a window that opened without keyboard focus.
 *
 * A test suite that closes one window and opens the next leaves the new window unfocused on GNOME
 * Wayland: after the focused window closes, no window has focus, and Mutter does not give focus to
 * the next Xwayland window that maps. GNOME also ignores that window's own `toFront` and
 * `requestFocus`. Spectre binds a Mutter window stream to the focused window, so it asks the window
 * manager to activate the window (EWMH `_NET_ACTIVE_WINDOW`) when the window's own request is
 * ignored. The still must show this window, never another one or a crop of the monitor.
 *
 * On other platforms the same steps only check that capture does not depend on focus. Skips
 * headless hosts and GitHub-hosted Windows (no interactive desktop for WGC).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnfocusedWindowCaptureValidationTest {

    private val fixture =
        SampleAppFixture(title = TITLE) {
            Box(Modifier.fillMaxSize().background(WINDOW_COLOR).testTag(WINDOW_TAG))
        }

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        assumeFalse(
            isGitHubHostedWindows(),
            "Windows Graphics Capture needs an interactive desktop; GitHub-hosted runners have none",
        )
        openAndCloseAFocusedWindow()
        fixture.start()
        runBlocking { fixture.automator.waitForNode(tag = WINDOW_TAG, timeout = 10.seconds) }
    }

    @AfterAll
    fun stop() {
        fixture.stop()
    }

    @Test
    fun `a window that opened without focus is captured as itself`() {
        val focusedBefore = onEdt { windowFrame().isFocused }
        if (isWaylandSession() && focusedBefore) {
            System.err.println(
                "UnfocusedWindowCaptureValidationTest: the window already had focus, so this run " +
                    "did not exercise window-manager activation"
            )
        }
        val index = windowIndex()
        val bounds = onEdt { windowFrame().bounds }
        val scale = onEdt { windowFrame().graphicsConfiguration.defaultTransform.scaleX }

        val image = screenshotUntilPainted(index)

        val expectedWidth = (bounds.width * scale).roundToInt()
        assertTrue(
            image.width >= expectedWidth - DECORATION_ALLOWANCE_PX && image.width <= expectedWidth,
            "The still must be this window (about $expectedWidth px wide); " +
                "got ${image.width}x${image.height}",
        )
        val centre = image.getRGB(image.width / 2, image.height / 2)
        assertTrue(
            colourDistance(centre, WINDOW_COLOR.toArgb()) <= COLOUR_TOLERANCE,
            "The centre must show the window colour; got #${hex(centre)}",
        )
    }

    /**
     * Shows a lone window, waits for it to get focus, and closes it. On GNOME Wayland no window has
     * focus afterwards.
     */
    private fun openAndCloseAFocusedWindow() {
        val decoy = onEdt {
            JFrame(DECOY_TITLE).apply {
                setBounds(DECOY_X, DECOY_Y, DECOY_SIZE_PX, DECOY_SIZE_PX)
                isVisible = true
            }
        }
        val deadline = System.nanoTime() + DECOY_FOCUS_TIMEOUT.inWholeNanoseconds
        while (!onEdt { decoy.isFocused } && System.nanoTime() < deadline) {
            Thread.sleep(PAINT_POLL_MS)
        }
        onEdt { decoy.dispose() }
    }

    private fun windowIndex(): Int {
        fixture.automator.refreshWindows()
        val index = fixture.automator.windows.indexOfFirst { !it.isPopup }
        check(index >= 0) { "The window is not tracked" }
        return index
    }

    private fun screenshotUntilPainted(index: Int): BufferedImage {
        val deadline = System.nanoTime() + PAINT_TIMEOUT.inWholeNanoseconds
        var image = fixture.automator.screenshot(windowIndex = index)
        while (!isWindowColourAtCentre(image) && System.nanoTime() < deadline) {
            Thread.sleep(PAINT_POLL_MS)
            image = fixture.automator.screenshot(windowIndex = index)
        }
        return image
    }

    private fun isWindowColourAtCentre(image: BufferedImage): Boolean =
        colourDistance(image.getRGB(image.width / 2, image.height / 2), WINDOW_COLOR.toArgb()) <=
            COLOUR_TOLERANCE

    private fun windowFrame(): Frame = Frame.getFrames().first { it.title == TITLE && it.isShowing }

    private companion object {
        const val TITLE = "Spectre unfocused window capture validation"
        const val DECOY_TITLE = "Spectre focus decoy"
        const val WINDOW_TAG = "unfocused.window"
        val WINDOW_COLOR = Color(0xFF0F766E)
        const val DECOY_X = 120
        const val DECOY_Y = 120
        const val DECOY_SIZE_PX = 240
        val DECOY_FOCUS_TIMEOUT = 3.seconds
        // A client-area still (Linux X11) is narrower than the window by its side decorations.
        const val DECORATION_ALLOWANCE_PX = 40
        // Display colour management can shift a solid colour a little in a native still.
        const val COLOUR_TOLERANCE = 40
        val PAINT_TIMEOUT = 10.seconds
        const val PAINT_POLL_MS = 100L

        fun isGitHubHostedWindows(): Boolean =
            System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true) &&
                System.getenv("RUNNER_ENVIRONMENT") == "github-hosted"

        fun colourDistance(argb: Int, expected: Int): Int {
            fun channel(value: Int, shift: Int) = (value shr shift) and 0xFF
            return listOf(16, 8, 0).maxOf { shift ->
                abs(channel(argb, shift) - channel(expected, shift))
            }
        }

        fun hex(argb: Int): String = "%06X".format(argb and 0xFFFFFF)

        fun <T> onEdt(block: () -> T): T {
            if (SwingUtilities.isEventDispatchThread()) return block()
            val result = AtomicReference<Result<T>>()
            SwingUtilities.invokeAndWait { result.set(runCatching(block)) }
            return result.get().getOrThrow()
        }
    }
}
