package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.semantics.SemanticsOwner
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
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
     */
    val composeContentOrigin: Point
        get() =
            composePanel?.locationOnScreen
                ?: (window as? JFrame)?.contentPane?.locationOnScreen
                ?: window.locationOnScreen

    val composeSurfaceBoundsOnScreen: Rectangle
        get() = readOnEdt {
            composePanel?.let { Rectangle(it.locationOnScreen, it.size) }
                ?: (window as? JFrame)?.contentPane?.let { Rectangle(it.locationOnScreen, it.size) }
                ?: Rectangle(window.locationOnScreen, window.size)
        }
}
