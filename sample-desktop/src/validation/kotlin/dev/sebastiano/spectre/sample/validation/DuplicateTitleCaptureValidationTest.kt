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
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame
import javax.swing.JPanel
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
 * Live acceptance for #611: native capture of a window whose title another window of the same
 * process also has.
 *
 * Windows and Linux X11 helpers find a titled window by its title, so a shared title used to make
 * every native still fail, and `waitForVisualIdle` reported every sample as unsampleable. Spectre
 * now selects such a window by its owner process and on-screen frame (#606) instead. It still fails
 * clearly when the frame is shared too. macOS is unchanged: its helper tells shared titles apart
 * itself. On GNOME Wayland, Spectre binds a Mutter window stream to the window itself (#642), so
 * neither the title nor the frame selects anything and the still is simply the window.
 *
 * Skips headless hosts and GitHub-hosted Windows (no interactive desktop for WGC).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DuplicateTitleCaptureValidationTest {

    private val fixture =
        SampleAppFixture(title = SHARED_TITLE) {
            Box(Modifier.fillMaxSize().background(OWNER_COLOR).testTag(OWNER_TAG))
        }

    private var twin: JFrame? = null

    @BeforeAll
    fun start() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Needs a real AWT display")
        assumeFalse(
            isGitHubHostedWindows(),
            "Windows Graphics Capture needs an interactive desktop; GitHub-hosted runners have none",
        )
        fixture.start()
        runBlocking { fixture.automator.waitForNode(tag = OWNER_TAG, timeout = 10.seconds) }
        twin = onEdt { openTwin(ownerFrame()) }
    }

    @AfterAll
    fun stop() {
        twin?.let { window -> onEdt { window.dispose() } }
        fixture.stop()
    }

    @Test
    fun `window screenshot of a window whose title is shared is a native still of that window`() {
        val index = ownerWindowIndex()

        val ownerBounds = onEdt { Rectangle(ownerFrame().bounds) }
        val scale = onEdt { ownerFrame().graphicsConfiguration.defaultTransform.scaleX }
        val image = screenshotUntilPainted(index)

        // The still is the window or its client area; either way it is far larger than the twin.
        // On Wayland it is the whole window from its own Mutter stream, so the twin cannot show.
        val ownerWidth = (ownerBounds.width * scale).roundToInt()
        assertTrue(
            image.width >= ownerWidth - DECORATION_ALLOWANCE_PX && image.width <= ownerWidth,
            "The still must be the owner window (about $ownerWidth px wide), not the twin " +
                "(${(TWIN_WIDTH_PX * scale).roundToInt()} px); got ${image.width}x${image.height}",
        )
        val centre = image.getRGB(image.width / 2, image.height / 2)
        assertTrue(
            colourDistance(centre, OWNER_COLOR.toArgb()) <= COLOUR_TOLERANCE,
            "The centre must show the owner colour; got #${hex(centre)}",
        )
    }

    @Test
    fun `waitForVisualIdle settles while two windows share a title`() {
        ownerWindowIndex()

        runBlocking { fixture.automator.waitForVisualIdle(timeout = 15.seconds, stableFrames = 2) }
    }

    @Test
    fun `a window that shares both its title and its frame still fails clearly`() {
        assumeFalse(
            System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true),
            "The macOS helper tells windows with the same title apart itself",
        )
        assumeFalse(
            isWaylandSession(),
            "Wayland binds a Mutter stream to the window itself; a shared frame selects nothing",
        )
        val index = ownerWindowIndex()
        val twinWindow = checkNotNull(twin)
        val twinBounds = onEdt { Rectangle(twinWindow.bounds) }
        try {
            onEdt { twinWindow.bounds = Rectangle(ownerFrame().bounds) }

            val error =
                assertFailsWith<IllegalStateException> {
                    fixture.automator.screenshot(windowIndex = index)
                }

            assertTrue(error.message.orEmpty().contains("frame"), error.message)
        } finally {
            onEdt { twinWindow.bounds = twinBounds }
        }
    }

    private fun ownerWindowIndex(): Int {
        fixture.automator.refreshWindows()
        val index = fixture.automator.windows.indexOfFirst { !it.isPopup }
        check(index >= 0) { "The owner window is not tracked" }
        return index
    }

    private fun screenshotUntilPainted(index: Int): BufferedImage {
        val deadline = System.nanoTime() + PAINT_TIMEOUT.inWholeNanoseconds
        var image = fixture.automator.screenshot(windowIndex = index)
        while (!isOwnerColourAtCentre(image) && System.nanoTime() < deadline) {
            Thread.sleep(PAINT_POLL_MS)
            image = fixture.automator.screenshot(windowIndex = index)
        }
        return image
    }

    private fun isOwnerColourAtCentre(image: BufferedImage): Boolean =
        colourDistance(image.getRGB(image.width / 2, image.height / 2), OWNER_COLOR.toArgb()) <=
            COLOUR_TOLERANCE

    private fun ownerFrame(): Frame =
        Frame.getFrames().first { it.title == SHARED_TITLE && it.isShowing && it !== twin }

    /**
     * A second window of this process with the same title. It sits over the owner's top-left
     * corner, away from the owner's centre, so Linux X11 (which reads visible pixels) still sees
     * the owner colour there.
     */
    private fun openTwin(owner: Frame): JFrame =
        JFrame(SHARED_TITLE).apply {
            focusableWindowState = false
            contentPane = JPanel().apply { background = java.awt.Color(TWIN_RGB) }
            setBounds(
                owner.x + TWIN_OFFSET_PX,
                owner.y + TWIN_OFFSET_PX,
                TWIN_WIDTH_PX,
                TWIN_HEIGHT_PX,
            )
            isVisible = true
        }

    private companion object {
        const val SHARED_TITLE = "Spectre duplicate title capture validation"
        const val OWNER_TAG = "duplicate.owner"
        val OWNER_COLOR = Color(0xFF1E3A8A)
        const val TWIN_RGB = 0xE07020
        const val TWIN_OFFSET_PX = 20
        const val TWIN_WIDTH_PX = 200
        const val TWIN_HEIGHT_PX = 120
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
