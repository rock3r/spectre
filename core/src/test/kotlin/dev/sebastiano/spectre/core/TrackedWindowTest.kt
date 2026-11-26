@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.awt.ComposePanel
import java.awt.Dimension
import java.awt.GraphicsEnvironment
import javax.swing.JFrame
import javax.swing.JPanel
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.condition.EnabledIf

@EnabledIf("liveAwtAvailable")
class TrackedWindowTest {

    @Test
    fun `hidden embedded panel keeps its own layout origin for snapshot geometry`() {
        assumeTrue(composeNativeAvailable(), "Compose native runtime is absent")
        SwingUtilities.invokeAndWait {
            val panel = ComposePanel().apply { setBounds(31, 47, 120, 80) }
            val host = JPanel(null).apply { add(panel) }
            val window =
                JFrame("Test").apply {
                    contentPane = host
                    size = Dimension(300, 240)
                    setLocation(100, 120)
                    isVisible = true
                }
            try {
                panel.isVisible = false
                val tracked = TrackedWindow("embedded", window, panel, isPopup = false)
                val contentOrigin = window.contentPane.locationOnScreen

                assertEquals(contentOrigin.x + 31, tracked.composeContentOrigin.x)
                assertEquals(contentOrigin.y + 47, tracked.composeContentOrigin.y)
                assertEquals(120, tracked.composeSurfaceBoundsOnScreen.width)
                assertEquals(80, tracked.composeSurfaceBoundsOnScreen.height)
            } finally {
                window.dispose()
            }
        }
    }

    @Test
    fun `pointer target rejects a hidden embedded panel`() {
        assumeTrue(composeNativeAvailable(), "Compose native runtime is absent")
        SwingUtilities.invokeAndWait {
            val window = JFrame("Test")
            val panel = ComposePanel()
            window.contentPane.add(panel)
            window.pack()
            val tracked = TrackedWindow("embedded", window, panel, isPopup = false)
            try {
                assertFailsWith<IllegalStateException> {
                    checkPointerSurfaceShowing(tracked, "click", "node")
                }
            } finally {
                window.dispose()
            }
        }
    }

    @Test
    fun `TrackedWindow stores surfaceId and popup flag`() {
        val window = JFrame("Test")
        val tracked =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        assertEquals("main:0", tracked.surfaceId)
        assertEquals(false, tracked.isPopup)
        window.dispose()
    }

    @Test
    fun `TrackedWindows with same surfaceId and window are equal`() {
        val window = JFrame("Test")
        val a =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        val b =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        assertEquals(a, b)
        window.dispose()
    }

    @Test
    fun `TrackedWindows with different surfaceId are not equal`() {
        val window = JFrame("Test")
        val a =
            TrackedWindow(
                surfaceId = "main:0",
                window = window,
                composePanel = null,
                isPopup = false,
            )
        val b =
            TrackedWindow(
                surfaceId = "popup:1",
                window = window,
                composePanel = null,
                isPopup = true,
            )
        assertNotEquals(a, b)
        window.dispose()
    }

    companion object {

        @JvmStatic
        fun liveAwtAvailable(): Boolean =
            !GraphicsEnvironment.isHeadless() &&
                (!detectMacOs() || System.getProperty("spectre.test.liveAwt").toBoolean())

        private fun composeNativeAvailable(): Boolean {
            val platform =
                when {
                    detectMacOs() -> "macos"
                    System.getProperty("os.name").startsWith("Windows") -> "windows"
                    else -> "linux"
                }
            val arch =
                if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64"
            return ComposePanel::class
                .java
                .classLoader
                .getResource("libskiko-$platform-$arch.so.sha256") != null
        }
    }
}
