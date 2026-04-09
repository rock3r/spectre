package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Container
import java.awt.Window

class WindowTracker {

    @Volatile private var _trackedWindows: List<TrackedWindow> = emptyList()
    private var pendingWindows = mutableListOf<TrackedWindow>()
    private var surfaceIndex = 0

    val trackedWindows: List<TrackedWindow>
        get() = _trackedWindows

    fun refresh() = readOnEdt {
        pendingWindows = mutableListOf()
        surfaceIndex = 0

        val topLevelWindows = Window.getWindows().filter { it.isShowing && it.owner == null }
        for (window in topLevelWindows) {
            when (window) {
                is ComposeWindow -> trackComposeWindow(window)
                else -> trackEmbeddedPanels(window)
            }
        }
        _trackedWindows = pendingWindows.toList()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun trackComposeWindow(window: ComposeWindow) {
        if (window.semanticsOwners.isNotEmpty()) {
            val panel = findComposePanels(window).firstOrNull()
            addTrackedWindow(window, panel, "window", isPopup = false)
        }
        trackOwnedPopups(window)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun trackOwnedPopups(owner: Window) {
        val visibleOwned = owner.ownedWindows.filter { it.isShowing }
        for (owned in visibleOwned) {
            when (owned) {
                is ComposeWindow -> {
                    if (owned.semanticsOwners.isNotEmpty()) {
                        val panel = findComposePanels(owned).firstOrNull()
                        addTrackedWindow(owned, panel, "popup", isPopup = true)
                    }
                }
                else -> trackActivePanels(owned, "popup", isPopup = true)
            }
            trackOwnedPopups(owned)
        }
    }

    private fun trackEmbeddedPanels(window: Window) {
        trackActivePanels(window, "embedded", isPopup = false)
        trackOwnedPopups(window)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun trackActivePanels(window: Window, prefix: String, isPopup: Boolean) {
        val panels = findComposePanels(window)
        for (panel in panels) {
            if (panel.semanticsOwners.isNotEmpty()) {
                addTrackedWindow(window, panel, prefix, isPopup)
            }
        }
    }

    private fun addTrackedWindow(
        window: Window,
        panel: ComposePanel?,
        prefix: String,
        isPopup: Boolean,
    ) {
        pendingWindows +=
            TrackedWindow(
                surfaceId = "$prefix:$surfaceIndex",
                window = window,
                composePanel = panel,
                isPopup = isPopup,
            )
        surfaceIndex++
    }
}

fun findComposePanels(container: Container): List<ComposePanel> {
    val result = mutableListOf<ComposePanel>()
    findComposePanelsRecursive(container, result)
    return result
}

private fun findComposePanelsRecursive(container: Container, result: MutableList<ComposePanel>) {
    for (child in container.components) {
        if (child is ComposePanel) {
            result += child
        } else if (child is Container) {
            findComposePanelsRecursive(child, result)
        }
    }
}
