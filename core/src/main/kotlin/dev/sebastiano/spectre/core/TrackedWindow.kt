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
                ?: Point(window.x, window.y)

    /**
     * Compose surface bounds in screen coordinates.
     *
     * Tries the panel, then Swing content panes (Frame / Dialog), then the window itself. Each
     * candidate is skipped when `locationOnScreen` is unavailable so a single not-yet-showing
     * intermediate does not fail the whole read (#362 attach `windows()` mapping).
     */
    val composeSurfaceBoundsOnScreen: Rectangle
        get() = readOnEdt {
            screenBoundsOrNull(composePanel)
                ?: screenBoundsOrNull((window as? JFrame)?.contentPane)
                ?: screenBoundsOrNull((window as? JDialog)?.contentPane)
                ?: screenBoundsOrNull(window)
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
