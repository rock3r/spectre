@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.sample.validation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import dev.sebastiano.spectre.core.InternalSpectreApi
import dev.sebastiano.spectre.core.isWaylandSession
import java.awt.BorderLayout
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JWindow
import javax.swing.SwingUtilities
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Live acceptance for #605: a Compose popup hosted in an untitled `javax.swing.JWindow` owned by a
 * `ComposeWindow`. Compose Pi hosts its non-focusable popups this way, and IntelliJ heavyweight
 * popups do too.
 *
 * AWT has no title for a JWindow, so the native helpers cannot find it by title. They find it by
 * its on-screen frame and owner process instead. This test drives the real helpers:
 * - `screenshot(windowIndex)` of the popup returns a native still of the popup, not of its owner.
 * - `waitForVisualIdle` settles while the popup is open, instead of reporting every sample as
 *   unsampleable.
 *
 * Runs on macOS (ScreenCaptureKit), Windows (Windows Graphics Capture, interactive desktop only),
 * and Linux X11/Xvfb. On Linux Wayland the portal cannot select a window by frame, so the test
 * asserts the clear failure message instead and skips the visual-idle check. Skips headless hosts,
 * GitHub-hosted Windows (no interactive desktop for WGC), and macOS hosts where the capture helper
 * has no Screen Recording permission.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JWindowPopupCaptureValidationTest {

    private val fixture =
        SampleAppFixture(title = OWNER_TITLE) {
            Box(Modifier.fillMaxSize().background(OWNER_COLOR).testTag(OWNER_TAG))
        }

    private var popup: JWindow? = null

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        assumeFalse(
            isGitHubHostedWindows(),
            "Windows Graphics Capture needs an interactive desktop; GitHub-hosted runners have none",
        )
        fixture.start()
        runBlocking { fixture.automator.waitForNode(tag = OWNER_TAG, timeout = 10.seconds) }
        assumeMacOsScreenRecordingGranted()
        popup = onEdt { openPopup(ownerFrame()) }
        runBlocking { fixture.automator.waitForNode(tag = POPUP_TAG, timeout = 10.seconds) }
    }

    @AfterAll
    fun stop() {
        popup?.let { window -> onEdt { window.dispose() } }
        fixture.stop()
    }

    @Test
    fun `window screenshot of a JWindow popup is a native still of the popup`() {
        val index = popupWindowIndex()
        if (isWaylandSession()) {
            val error =
                assertFailsWith<UnsupportedOperationException> {
                    fixture.automator.screenshot(windowIndex = index)
                }
            assertTrue(error.message.orEmpty().contains("Wayland"), error.message)
            return
        }

        val popupBounds = onEdt { Rectangle(checkNotNull(popup).bounds) }
        val scale = onEdt { checkNotNull(popup).graphicsConfiguration.defaultTransform.scaleX }
        // The first frames of a fresh ComposePanel can still be blank; poll for the painted colour
        // instead of asserting on the very first still.
        val image = screenshotUntilPainted(index)

        val expectedWidth = (popupBounds.width * scale).roundToInt()
        val expectedHeight = (popupBounds.height * scale).roundToInt()
        assertTrue(
            abs(image.width - expectedWidth) <= SIZE_TOLERANCE_PX &&
                abs(image.height - expectedHeight) <= SIZE_TOLERANCE_PX,
            "The still must be the popup (${expectedWidth}x$expectedHeight px), not its owner " +
                "window; got ${image.width}x${image.height}",
        )
        val centre = image.getRGB(image.width / 2, image.height / 2)
        assertTrue(
            colourDistance(centre, POPUP_COLOR.toArgb()) <= COLOUR_TOLERANCE,
            "The popup centre must show the popup colour; got #${hex(centre)}",
        )
    }

    @Test
    fun `waitForVisualIdle settles while a JWindow popup is open`() {
        assumeFalse(
            isWaylandSession(),
            "Wayland cannot capture an untitled window natively; visual idle cannot sample it",
        )
        popupWindowIndex()

        runBlocking { fixture.automator.waitForVisualIdle(timeout = 15.seconds, stableFrames = 2) }
    }

    private fun popupWindowIndex(): Int {
        fixture.automator.refreshWindows()
        val target = checkNotNull(popup)
        val index = fixture.automator.windows.indexOfFirst { it.window === target }
        check(index >= 0) {
            "The JWindow popup is not tracked. Tracked windows: " +
                fixture.automator.windows.map { it.window.javaClass.name }
        }
        return index
    }

    private fun screenshotUntilPainted(index: Int): BufferedImage {
        val deadline = System.nanoTime() + PAINT_TIMEOUT.inWholeNanoseconds
        var image = fixture.automator.screenshot(windowIndex = index)
        while (!isPopupColourAtCentre(image) && System.nanoTime() < deadline) {
            Thread.sleep(PAINT_POLL_MS)
            image = fixture.automator.screenshot(windowIndex = index)
        }
        return image
    }

    private fun isPopupColourAtCentre(image: BufferedImage): Boolean =
        colourDistance(image.getRGB(image.width / 2, image.height / 2), POPUP_COLOR.toArgb()) <=
            COLOUR_TOLERANCE

    /**
     * On macOS, ScreenCaptureKit needs Screen Recording permission for the capture helper. Without
     * it every native still fails, so this test cannot tell a JWindow regression from a missing
     * grant. Probe with the owner window, which has a title and so does not depend on #605.
     */
    private fun assumeMacOsScreenRecordingGranted() {
        if (!System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)) return
        val denied =
            runCatching { fixture.automator.screenshot(windowIndex = ownerWindowIndex()) }
                .exceptionOrNull()
                ?.let { generateSequence(it) { error -> error.cause } }
                ?.any { it.message.orEmpty().contains("Screen Recording: DENIED") } == true
        assumeFalse(
            denied,
            "The macOS capture helper has no Screen Recording permission; grant it with " +
                "`spectre permissions request` and rerun",
        )
    }

    private fun ownerWindowIndex(): Int {
        fixture.automator.refreshWindows()
        return fixture.automator.windows.indexOfFirst { !it.isPopup }
    }

    private fun ownerFrame(): Frame =
        Frame.getFrames().first { it.title == OWNER_TITLE && it.isShowing }

    private fun openPopup(owner: Frame): JWindow =
        JWindow(owner).apply {
            // Compose Pi and IntelliJ heavyweight popups are non-focusable JWindows.
            focusableWindowState = false
            contentPane.add(
                ComposePanel().apply {
                    setContent {
                        Box(Modifier.fillMaxSize().background(POPUP_COLOR).testTag(POPUP_TAG))
                    }
                },
                BorderLayout.CENTER,
            )
            setBounds(
                owner.x + POPUP_OFFSET_PX,
                owner.y + POPUP_OFFSET_PX,
                POPUP_WIDTH_PX,
                POPUP_HEIGHT_PX,
            )
            isVisible = true
        }

    private companion object {
        const val OWNER_TITLE = "Spectre JWindow popup capture validation"
        const val OWNER_TAG = "jwindow.owner"
        const val POPUP_TAG = "jwindow.popup"
        val OWNER_COLOR = Color(0xFF1E3A8A)
        val POPUP_COLOR = Color(0xFFE07020)
        const val POPUP_OFFSET_PX = 80
        const val POPUP_WIDTH_PX = 240
        const val POPUP_HEIGHT_PX = 160
        const val SIZE_TOLERANCE_PX = 2
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
