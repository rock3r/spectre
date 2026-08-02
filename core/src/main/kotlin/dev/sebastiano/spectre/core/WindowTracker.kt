@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.awt.ComposePanel
import androidx.compose.ui.awt.ComposeWindow
import java.awt.Container
import java.awt.Window
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@InternalSpectreApi
public class WindowTracker
internal constructor(
    private val allWindows: () -> Array<Window>,
    private val requiresEdt: Boolean,
) {

    public constructor() : this(Window::getWindows, requiresEdt = true)

    private val surfaceIdAssigner = SurfaceIdAssigner()

    private val _trackedWindows = MutableStateFlow<List<TrackedWindow>>(emptyList())

    /**
     * Windows observed as [Window.isShowing] at least once during this tracker's lifetime.
     *
     * Used so #362 delayed-show hosts (displayable + semantics, never yet shown) remain
     * discoverable, while HIDE_ON_CLOSE / setVisible(false) frames that **have** shown before are
     * not re-listed after dismiss (they stay displayable with stale semantics).
     */
    private val everShownWindows = mutableListOf<WeakReference<Window>>()

    /**
     * Live view of the currently tracked surfaces. Backed by a [StateFlow] so that subscribers
     * (e.g. `RecompositionMonitor`) can reconcile against window-set changes without polling.
     * Synchronous callers read [StateFlow.value]; the flow follows the standard
     * distinctUntilChanged contract, so two refreshes that produce equal lists emit only once.
     */
    public val trackedWindows: StateFlow<List<TrackedWindow>> = _trackedWindows.asStateFlow()

    public fun refresh() {
        if (requiresEdt) {
            readOnEdt { refreshOnCurrentThread() }
        } else {
            refreshOnCurrentThread()
        }
    }

    private fun refreshOnCurrentThread() {
        val pending = mutableListOf<TrackedWindow>()
        // Iterate every top-level window (`owner == null`) regardless of visibility — Swing's
        // `SharedOwnerFrame` is a hidden parent for `JDialog(null as Frame?, ...)`, so filtering
        // by `isShowing` here would drop the dialog along with it. Visibility is enforced per
        // candidate further down (a hidden parent only contributes through its visible
        // descendants).
        val topLevelWindows = allWindows().filter { it.owner == null }
        for (window in topLevelWindows) {
            if (window.isShowing) markEverShown(window)
            when {
                // #362 delayed-show: admit never-yet-shown displayable Compose hosts with
                // semantics. Once a window has been showing, only re-list while isShowing so
                // HIDE_ON_CLOSE dismissals are not re-admitted with stale trees.
                window is ComposeWindow && shouldTrackTopLevel(window) ->
                    trackComposeWindow(pending, window)
                shouldTrackTopLevel(window) -> trackEmbeddedPanels(pending, window)
                // Not displayable / not trackable: still walk owned dialogs in case any show.
                else -> trackOwnedPopups(pending, window)
            }
        }
        pruneEverShown()
        _trackedWindows.value = pending.toList()
    }

    /**
     * True when [window] is currently showing, or is a delayed-show candidate (displayable, never
     * observed showing, will be filtered further by semantics/panel presence in track helpers).
     */
    private fun shouldTrackTopLevel(window: Window): Boolean {
        if (window.isShowing) return true
        if (!window.isDisplayable) return false
        return !hasEverShown(window)
    }

    private fun markEverShown(window: Window) {
        if (hasEverShown(window)) return
        everShownWindows += WeakReference(window)
    }

    private fun hasEverShown(window: Window): Boolean {
        pruneEverShown()
        return everShownWindows.any { it.get() === window }
    }

    private fun pruneEverShown() {
        everShownWindows.removeAll { it.get() == null }
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
        // Owned popups: keep isShowing (not mere isDisplayable). HIDE_ON_CLOSE dialogs remain
        // displayable with stale semantics after dismiss; re-listing them would pollute
        // windows()/allNodes() with closed surfaces. Delayed-show for owned dialogs is rare
        // compared to top-level onboarding hosts (handled above via isDisplayable).
        val candidateOwned = owner.ownedWindows.filter { it.isShowing && it !in skip }
        for (owned in candidateOwned) {
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
        // Identity for a "normal" surface is (Window, ComposePanel?). Two refreshes that find the
        // same JFrame and the same embedded ComposePanel resolve to the same surfaceId.
        pending +=
            TrackedWindow(
                surfaceId = surfaceIdAssigner.assign(prefix, window, panel),
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
        // Overlay-layer identity is the internal JDialog (`layer.window`) — that's the stable
        // handle that survives across rediscovery passes, even though the lambda that reads its
        // semantics is freshly built each call.
        val tracked =
            TrackedWindow(
                surfaceId = surfaceIdAssigner.assign("overlay", layer.window),
                window = layer.window,
                composePanel = null,
                isPopup = true,
            )
        tracked.overlaySemanticsOwners = layer.semanticsOwnersAccessor
        pending += tracked
    }

    internal companion object {
        fun empty(): WindowTracker = WindowTracker({ emptyArray() }, requiresEdt = false)
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
