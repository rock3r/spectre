package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.semantics.SemanticsOwner
import java.awt.Component
import java.awt.Dialog
import java.awt.IllegalComponentStateException
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JDialog
import javax.swing.JFrame

@InternalSpectreApi
public data class TrackedWindow(
    val surfaceId: String,
    val window: Window,
    val composePanel: ComposePanel?,
    val isPopup: Boolean,
) {

    /**
     * Reflective overlay accessor used for Compose Desktop's `OnWindow` popup layers
     * (`compose.layers.type=WINDOW`). Compose hosts those popups inside an internal
     * `WindowComposeSceneLayer` whose mediator isn't reachable through any public API;
     * `OverlayLayerInspector` resolves them by reflection and `SemanticsReader` dispatches to this
     * accessor when present. `null` for every other tracked window — the existing `composePanel` /
     * `ComposeWindow` paths handle those.
     *
     * Held outside the primary constructor so [equals]/[hashCode] (generated from primary-ctor
     * parameters only) ignore the lambda; otherwise lambda reference equality would make every
     * rediscovery look like a fresh window and break `StateFlow.distinctUntilChanged` semantics on
     * `WindowTracker.trackedWindows`.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    internal var overlaySemanticsOwners: (() -> Collection<SemanticsOwner>)? = null

    /**
     * The screen location of the Compose content origin.
     *
     * For ComposeWindow (a JFrame), the Compose content renders inside the content pane, which sits
     * below the title bar. For embedded ComposePanels, the panel's own location is used. This is
     * critical for correct coordinate mapping: boundsInWindow is relative to the content area, not
     * the window frame.
     *
     * Falls through candidates when a component is not yet on-screen
     * (`IllegalComponentStateException`) so attach probes against delayed-show / custom-chrome
     * windows still get a usable origin (#362).
     */
    val composeContentOrigin: Point
        get() =
            locationOnScreenOrNull(composePanel)
                ?: locationOnScreenOrNull((window as? JFrame)?.contentPane)
                ?: locationOnScreenOrNull((window as? JDialog)?.contentPane)
                ?: locationOnScreenOrNull(window)
                ?: layoutScreenOrigin(composePanel, window)
                ?: layoutScreenOrigin((window as? JFrame)?.contentPane, window)
                ?: layoutScreenOrigin((window as? JDialog)?.contentPane, window)
                ?: Point(window.x, window.y)

    /**
     * Compose surface bounds in screen coordinates.
     *
     * Tries live `locationOnScreen` first (panel → content pane → window). When the host is
     * displayable but not yet showing, falls back to **window-relative layout** (child offsets
     * summed to the top-level window, then offset by the window's layout x/y) so decorated frames
     * and non-full-size panels keep correct surface geometry during delayed-show (#362).
     */
    val composeSurfaceBoundsOnScreen: Rectangle
        get() = readOnEdt {
            screenBoundsOrNull(composePanel)
                ?: screenBoundsOrNull((window as? JFrame)?.contentPane)
                ?: screenBoundsOrNull((window as? JDialog)?.contentPane)
                ?: screenBoundsOrNull(window)
                ?: layoutScreenBounds(composePanel, window)
                ?: layoutScreenBounds((window as? JFrame)?.contentPane, window)
                ?: layoutScreenBounds((window as? JDialog)?.contentPane, window)
                ?: Rectangle(
                    window.x,
                    window.y,
                    window.width.coerceAtLeast(0),
                    window.height.coerceAtLeast(0),
                )
        }

    /** Title from [java.awt.Frame] or [Dialog]; null for bare [Window]. */
    val windowTitle: String?
        get() =
            when (val w = window) {
                is java.awt.Frame -> w.title
                is Dialog -> w.title
                else -> null
            }
}

private fun locationOnScreenOrNull(component: Component?): Point? {
    if (component == null) return null
    return try {
        component.locationOnScreen
    } catch (_: IllegalComponentStateException) {
        null
    }
}

private fun screenBoundsOrNull(component: Component?): Rectangle? {
    if (component == null) return null
    return try {
        Rectangle(component.locationOnScreen, component.size)
    } catch (_: IllegalComponentStateException) {
        null
    }
}

/**
 * Screen-space origin for [component] when `locationOnScreen` is unavailable: sum parent offsets up
 * to [window], then add the window's layout position.
 */
private fun layoutScreenOrigin(component: Component?, window: Window): Point? {
    val inWindow = boundsInWindow(component, window) ?: return null
    return Point(window.x + inWindow.x, window.y + inWindow.y)
}

private fun layoutScreenBounds(component: Component?, window: Window): Rectangle? {
    val inWindow = boundsInWindow(component, window) ?: return null
    return Rectangle(window.x + inWindow.x, window.y + inWindow.y, inWindow.width, inWindow.height)
}

private fun boundsInWindow(component: Component?, window: Window): Rectangle? {
    if (component == null) return null
    val width = component.width
    val height = component.height
    if (width <= 0 || height <= 0) return null
    var x = 0
    var y = 0
    var current: Component? = component
    while (current != null && current !== window) {
        x += current.x
        y += current.y
        current = current.parent
    }
    if (current !== window) return null
    return Rectangle(x, y, width, height)
}
