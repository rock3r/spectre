@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Container
import java.awt.Window

@InternalSpectreApi
class WindowTracker {

    @Volatile private var _trackedWindows: List<TrackedWindow> = emptyList()

    val trackedWindows: List<TrackedWindow>
        get() = _trackedWindows

    fun refresh() = readOnEdt {
        val pending = mutableListOf<TrackedWindow>()
        // Iterate every top-level window (`owner == null`) regardless of visibility — Swing's
        // `SharedOwnerFrame` is a hidden parent for `JDialog(null as Frame?, ...)`, so filtering
        // by `isShowing` here would drop the dialog along with it. Visibility is enforced per
        // candidate further down (a hidden parent only contributes through its visible
        // descendants).
        val topLevelWindows = Window.getWindows().filter { it.owner == null }
        for (window in topLevelWindows) {
            when {
                window is ComposeWindow && window.isShowing -> trackComposeWindow(pending, window)
                window.isShowing -> trackEmbeddedPanels(pending, window)
                // Hidden parent (e.g. SharedOwnerFrame) — skip its own panels but still walk its
                // owned dialogs in case any of them are showing.
                else -> trackOwnedPopups(pending, window)
            }
        }
        _trackedWindows = pending.toList()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun trackComposeWindow(pending: MutableList<TrackedWindow>, window: ComposeWindow) {
        if (window.semanticsOwners.isNotEmpty()) {
            val panel = findComposePanels(window).firstOrNull()
            addTrackedWindow(pending, window, panel, "window", isPopup = false)
        }
        // Compose Desktop's `OnWindow` popup layer hosts the popup inside an internal
        // `WindowComposeSceneLayer` whose `JDialog` won't be discovered as a `ComposePanel` host
        // (its content sits in a private `JLayeredPaneWithTransparencyHack`, not a ComposePanel).
        // Surface those layers through the reflective `OverlayLayerInspector` so each one becomes
        // its own tracked window with a semantics accessor that points at the layer's mediator.
        val overlayLayers = OverlayLayerInspector.findOverlayLayerWindows(window)
        for (layer in overlayLayers) {
            if (layer.semanticsOwnersAccessor().isNotEmpty()) {
                addOverlayTrackedWindow(pending, layer)
            }
        }
        trackOwnedPopups(pending, window, skip = overlayLayers.mapTo(HashSet()) { it.window })
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun trackOwnedPopups(
        pending: MutableList<TrackedWindow>,
        owner: Window,
        skip: Set<Window> = emptySet(),
    ) {
        val visibleOwned = owner.ownedWindows.filter { it.isShowing && it !in skip }
        for (owned in visibleOwned) {
            when (owned) {
                is ComposeWindow -> {
                    if (owned.semanticsOwners.isNotEmpty()) {
                        val panel = findComposePanels(owned).firstOrNull()
                        addTrackedWindow(pending, owned, panel, "popup", isPopup = true)
                    }
                }
                else -> trackActivePanels(pending, owned, "popup", isPopup = true)
            }
            trackOwnedPopups(pending, owned, skip)
        }
    }

    private fun trackEmbeddedPanels(pending: MutableList<TrackedWindow>, window: Window) {
        trackActivePanels(pending, window, "embedded", isPopup = false)
        trackOwnedPopups(pending, window)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun trackActivePanels(
        pending: MutableList<TrackedWindow>,
        window: Window,
        prefix: String,
        isPopup: Boolean,
    ) {
        val panels = findComposePanels(window)
        for (panel in panels) {
            if (panel.semanticsOwners.isNotEmpty()) {
                addTrackedWindow(pending, window, panel, prefix, isPopup)
            }
        }
    }

    private fun addTrackedWindow(
        pending: MutableList<TrackedWindow>,
        window: Window,
        panel: ComposePanel?,
        prefix: String,
        isPopup: Boolean,
    ) {
        pending +=
            TrackedWindow(
                surfaceId = "$prefix:${pending.size}",
                window = window,
                composePanel = panel,
                isPopup = isPopup,
            )
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun addOverlayTrackedWindow(
        pending: MutableList<TrackedWindow>,
        layer: OverlayLayerEntry,
    ) {
        pending +=
            TrackedWindow(
                surfaceId = "overlay:${pending.size}",
                window = layer.window,
                composePanel = null,
                isPopup = true,
                overlaySemanticsOwners = layer.semanticsOwnersAccessor,
            )
    }
}

internal fun findComposePanels(container: Container): List<ComposePanel> {
    val result = mutableListOf<ComposePanel>()
    findComposePanelsRecursive(container, result)
    return result
}

private fun findComposePanelsRecursive(container: Container, result: MutableList<ComposePanel>) {
    for (child in container.components) {
        if (child is ComposePanel) {
            result += child
        }
        if (child is Container) {
            findComposePanelsRecursive(child, result)
        }
    }
}
