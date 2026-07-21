@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeFalse
import org.junit.jupiter.api.condition.EnabledIf

@EnabledIf("liveAwtAvailable")
class WindowIdentityResolverTest {

    @Test
    fun `resolve reports window and surface bounds with window-relative crop rect`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT")
        val frame = JFrame("identity-fixture")
        try {
            SwingUtilities.invokeAndWait {
                frame.setSize(400, 300)
                frame.setLocation(120, 80)
                frame.isVisible = true
            }
            // Give the peer a tick to become displayable.
            Thread.sleep(50)
            val tracked =
                TrackedWindow(
                    surfaceId = "surface-test",
                    window = frame,
                    composePanel = null,
                    isPopup = false,
                )
            val identity = WindowIdentityResolver.resolve(index = 0, tracked = tracked)
            assertEquals(0, identity.index)
            assertEquals("surface-test", identity.surfaceId)
            assertEquals("identity-fixture", identity.title)
            assertEquals(false, identity.isPopup)
            assertTrue(identity.scaleX > 0.0)
            assertTrue(identity.scaleY > 0.0)
            // Without an embedded panel, surface follows content/window; crop rect origin is
            // non-negative.
            assertTrue(identity.surfaceBoundsInWindow.x >= 0)
            assertTrue(identity.surfaceBoundsInWindow.y >= 0)
            assertTrue(identity.surfaceBoundsInWindow.width > 0)
            assertTrue(identity.surfaceBoundsInWindow.height > 0)
            // Screen-space surface must match window origin + relative crop.
            assertEquals(
                identity.windowBoundsOnScreen.x + identity.surfaceBoundsInWindow.x,
                identity.surfaceBoundsOnScreen.x,
            )
            assertEquals(
                identity.windowBoundsOnScreen.y + identity.surfaceBoundsInWindow.y,
                identity.surfaceBoundsOnScreen.y,
            )
            // Handle resolution is best-effort; on a realized frame we usually get a non-null peer.
            // Do not hard-fail if the JDK peer path is unavailable in this environment.
            if (identity.nativeHandle != null) {
                assertTrue(identity.nativeHandle != 0L)
            }
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    @Test
    fun `surfaceBoundsInWindow is relative to windowBoundsOnScreen origin`() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Requires non-headless AWT")
        val frame = JFrame("relative-crop")
        try {
            SwingUtilities.invokeAndWait {
                frame.setSize(500, 400)
                frame.setLocation(50, 60)
                frame.isVisible = true
            }
            Thread.sleep(50)
            val tracked =
                TrackedWindow(surfaceId = "s", window = frame, composePanel = null, isPopup = false)
            val identity = WindowIdentityResolver.resolve(0, tracked)
            val expected =
                Rectangle(
                    identity.surfaceBoundsOnScreen.x - identity.windowBoundsOnScreen.x,
                    identity.surfaceBoundsOnScreen.y - identity.windowBoundsOnScreen.y,
                    identity.surfaceBoundsOnScreen.width,
                    identity.surfaceBoundsOnScreen.height,
                )
            assertEquals(expected, identity.surfaceBoundsInWindow)
        } finally {
            SwingUtilities.invokeAndWait { frame.dispose() }
        }
    }

    companion object {
        @JvmStatic
        fun liveAwtAvailable(): Boolean =
            !GraphicsEnvironment.isHeadless() &&
                (!System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true) ||
                    System.getProperty("spectre.test.liveAwt").toBoolean())
    }
}
