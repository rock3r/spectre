package dev.sebastiano.spectre.core

import androidx.compose.ui.awt.ComposePanel
import java.awt.Window
import javax.swing.JFrame
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The host accessors on [AutomatorWindow] are published API (#566).
 *
 * This file deliberately has no file-level `@OptIn(InternalSpectreApi::class)`: reading
 * [AutomatorWindow.window], [AutomatorWindow.composePanel] and [AutomatorWindow.hostComponent]
 * below must compile without opting in. Only the fixture helper that builds the snapshot opts in,
 * because [TrackedWindow] itself stays an escape hatch.
 */
class AutomatorWindowHostAccessorsTest {

    @Test
    fun `host accessors are not marked as internal API`() {
        val annotated =
            AutomatorWindow::class.java.declaredMethods.filter { method ->
                method.name.removeSuffix("\$annotations") in HOST_GETTERS &&
                    method.isAnnotationPresent(InternalSpectreApi::class.java)
            }
        val missing = HOST_GETTERS.filter { getter ->
            AutomatorWindow::class.java.declaredMethods.none { it.name == getter }
        }
        assertTrue(missing.isEmpty(), "AutomatorWindow is missing public getters: $missing")
        assertTrue(annotated.isEmpty(), "Host accessors still marked internal: $annotated")
    }

    @Test
    fun `standalone window surface reports the window as its host component`() {
        assumeLiveAwtAvailable()
        val frame = JFrame("host-accessors")
        try {
            val window = automatorWindowFor(frame, composePanel = null)

            assertSame<Window>(frame, window.window)
            assertNull(window.composePanel)
            assertSame(frame, window.hostComponent)
        } finally {
            frame.dispose()
        }
    }

    @Test
    fun `embedded panel surface reports the panel as its host component`() {
        assumeLiveAwtAvailable()
        // ComposePanel must be created on the EDT; off it, construction throws on Windows.
        val (host, embedded) =
            readOnEdt {
                val frame = JFrame("host-accessors-embedded")
                frame to ComposePanel().also { frame.contentPane.add(it) }
            }
        try {
            val window = automatorWindowFor(host, composePanel = embedded)

            assertSame<Window>(host, window.window)
            assertSame(embedded, window.composePanel)
            assertSame(embedded, window.hostComponent)
        } finally {
            SwingUtilities.invokeAndWait { host.dispose() }
        }
    }

    @OptIn(InternalSpectreApi::class)
    private fun automatorWindowFor(window: Window, composePanel: ComposePanel?): AutomatorWindow =
        AutomatorWindow(
            windowIndex = 0,
            trackedWindow =
                TrackedWindow(
                    surfaceId = "embedded:0",
                    window = window,
                    composePanel = composePanel,
                    isPopup = false,
                ),
            nodes = emptyList(),
        )

    private companion object {
        val HOST_GETTERS = setOf("getWindow", "getComposePanel", "getHostComponent")
    }
}
