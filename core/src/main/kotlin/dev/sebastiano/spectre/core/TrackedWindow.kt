package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.semantics.SemanticsOwner
import java.awt.Point
import java.awt.Rectangle
import java.awt.Window
import javax.swing.JFrame

data class TrackedWindow
@OptIn(ExperimentalComposeUiApi::class)
internal constructor(
    val surfaceId: String,
    val window: Window,
    val composePanel: ComposePanel?,
    val isPopup: Boolean,
    /**
     * Reflective override that lets `WindowTracker` plug in a non-`ComposePanel` semantics source
     * for Compose Desktop's `OnWindow` popup layers (`compose.layers.type=WINDOW`). Compose hosts
     * those popups inside an internal `WindowComposeSceneLayer` whose mediator isn't reachable
     * through any public API; `OverlayLayerInspector` resolves them by reflection and
     * `SemanticsReader` dispatches to this accessor. `null` for every other tracked window — the
     * existing `composePanel` / `ComposeWindow` paths handle those.
     */
    internal val overlaySemanticsOwners: (() -> Collection<SemanticsOwner>)? = null,
) {

    /**
     * Stable public constructor preserved for source compatibility with anything that builds a
     * `TrackedWindow` directly (notably `core`'s own unit tests).
     */
    @OptIn(ExperimentalComposeUiApi::class)
    constructor(
        surfaceId: String,
        window: Window,
        composePanel: ComposePanel?,
        isPopup: Boolean,
    ) : this(surfaceId, window, composePanel, isPopup, overlaySemanticsOwners = null)

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
