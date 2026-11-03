@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsConfiguration
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import java.awt.Point
import java.awt.Rectangle

/**
 * Stable identifier for a node in the semantics tree across refreshes.
 *
 * Three parts:
 * - [surfaceId] — the surface this node belongs to (a window or popup root); may contain colons.
 * - [ownerIndex] — index of the SemanticsOwner inside that surface (Compose can host multiple).
 * - [nodeId] — the SemanticsNode id assigned by Compose, stable across recompositions as long as
 *   the node is not re-keyed or removed.
 *
 * The string form is `surfaceId:ownerIndex:nodeId` — used on the HTTP transport's
 * `ClickRequest.nodeKey` field and produced by [toString]. Parse with [parse]; parsing splits from
 * the right so colons inside [surfaceId] are preserved.
 */
public data class NodeKey(val surfaceId: String, val ownerIndex: Int, val nodeId: Int) {

    override fun toString(): String = "$surfaceId:$ownerIndex:$nodeId"

    public companion object {

        /**
         * Parses a string of the form `surfaceId:ownerIndex:nodeId` into a [NodeKey]. Throws
         * [IllegalArgumentException] on malformed input.
         */
        public fun parse(key: String): NodeKey {
            // Split from the right: last segment is nodeId, second-to-last is ownerIndex,
            // everything before is surfaceId (which may contain colons).
            val lastColon = key.lastIndexOf(':')
            require(lastColon > 0) { "Invalid NodeKey: $key" }
            val beforeLast = key.lastIndexOf(':', lastColon - 1)
            require(beforeLast >= 0) { "Invalid NodeKey: $key" }

            val surfaceId = key.substring(0, beforeLast)
            val ownerIndex = key.substring(beforeLast + 1, lastColon).toInt()
            val nodeId = key.substring(lastColon + 1).toInt()
            return NodeKey(surfaceId, ownerIndex, nodeId)
        }
    }
}

/**
 * Snapshot of a Compose `SemanticsNode` plus enough live geometry to act on it.
 *
 * Spectre reads the semantics tree on the EDT (where it must) and constructs `AutomatorNode`s with
 * **eagerly-snapshotted properties** ([testTag], [texts], [contentDescriptions], …) so callers can
 * read those fields from any thread without touching the tree again. Geometry properties (bounds,
 * hit-test rectangles) are computed lazily and may consult the underlying `SemanticsNode` if read
 * after a recomposition — treat them as snapshots that may not match the node's current on-screen
 * position after a subsequent refresh.
 *
 * Instances are produced by [ComposeAutomator.allNodes] / [ComposeAutomator.findByTestTag] and the
 * other query entry points; consumers should not construct them directly. The [key] uniquely
 * identifies the node across refreshes (see [NodeKey]).
 */
public class AutomatorNode
internal constructor(
    public val key: NodeKey,
    internal val semanticsNode: SemanticsNode,
    internal val trackedWindow: TrackedWindow,
    private val relations: NodeRelations = EmptyNodeRelations,
) {

    /** Stable identifier of the tracked window/surface this node belongs to. */
    public val surfaceId: String = trackedWindow.surfaceId

    // Eagerly snapshot all semantics properties at construction time (which runs on
    // the EDT inside readAllNodes) so callers can safely read them from any thread.
    public val testTag: String? = semanticsNode.config.getOrNull(SemanticsProperties.TestTag)
    public val texts: List<String> =
        semanticsNode.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    public val text: String? = texts.firstOrNull()
    public val editableText: String? =
        semanticsNode.config.getOrNull(SemanticsProperties.EditableText)?.text
    public val contentDescriptions: List<String> =
        semanticsNode.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    public val contentDescription: String? = contentDescriptions.firstOrNull()
    public val role: Role? = semanticsNode.config.getOrNull(SemanticsProperties.Role)
    public val isDisabled: Boolean =
        semanticsNode.config.getOrNull(SemanticsProperties.Disabled) != null
    public val isFocused: Boolean =
        semanticsNode.config.getOrNull(SemanticsProperties.Focused) == true
    public val isSelected: Boolean =
        semanticsNode.config.getOrNull(SemanticsProperties.Selected) == true
    /** True when the node declares a Compose `OnClick` semantics action. */
    public val isClickable: Boolean =
        semanticsNode.config.getOrNull(SemanticsActions.OnClick) != null

    /**
     * Toggle state of a checkbox, switch, or tri-state checkbox: [ToggleableState.On],
     * [ToggleableState.Off], or [ToggleableState.Indeterminate]. `null` when the node is not
     * toggleable.
     */
    public val toggleableState: ToggleableState? = semanticsNode.config.toggleableStateOrNull()

    /**
     * Value, range, and step count of a slider or progress indicator. `null` when the node has no
     * progress semantics. An indeterminate progress indicator reports
     * [ProgressBarRangeInfo.Indeterminate].
     */
    public val progressBarRangeInfo: ProgressBarRangeInfo? =
        semanticsNode.config.progressBarRangeInfoOrNull()
    public val isVisible: Boolean = true

    // Geometry is always read live: layout can change between node lookup and action,
    // so a snapshot would let click/screenshot drift to stale coordinates after scrolling
    // or recomposition-driven repositioning.
    /**
     * Layout bounds within the window's Compose surface, in physical (Compose) pixels.
     *
     * Values are already density-scaled: a 24.dp inset measures 48 here on a 2× display. Compare
     * against `Dp.roundToPx()` / `LocalDensity`-scaled values. For AWT/Robot input and recording
     * regions, use [boundsOnScreen] or [centerOnScreen], which divide by the display scale and add
     * the surface/panel screen origin; do not multiply this rect by density. For bounds relative to
     * the hosting AWT window, use [boundsInAwtWindow].
     */
    public val boundsInWindow: Rect
        get() = readOnEdt { semanticsNode.boundsInWindow }

    public val centerOnScreen: Point
        get() = readOnEdt {
            val bounds = semanticsNode.boundsInWindow
            val transform = trackedWindow.window.graphicsConfiguration.defaultTransform
            val contentOrigin = trackedWindow.composeContentOrigin
            composeBoundsToAwtCenter(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                scaleX = transform.scaleX.toFloat(),
                scaleY = transform.scaleY.toFloat(),
                panelScreenX = contentOrigin.x,
                panelScreenY = contentOrigin.y,
            )
        }

    /**
     * Bounds in global AWT screen coordinates, in logical units (the units `java.awt.Robot` and
     * `Component.getLocationOnScreen()` use). HiDPI-corrected: [boundsInWindow] divided by the
     * display scale, plus the Compose content's screen origin. See also [boundsInAwtWindow].
     */
    public val boundsOnScreen: Rectangle
        get() = readOnEdt {
            val bounds = semanticsNode.boundsInWindow
            val transform = trackedWindow.window.graphicsConfiguration.defaultTransform
            val contentOrigin = trackedWindow.composeContentOrigin
            composeBoundsToAwtRectangle(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                scaleX = transform.scaleX.toFloat(),
                scaleY = transform.scaleY.toFloat(),
                panelScreenX = contentOrigin.x,
                panelScreenY = contentOrigin.y,
            )
        }

    /**
     * Bounds relative to the top-left corner of the hosting AWT window ([AutomatorWindow.window]),
     * in AWT logical units.
     *
     * This is [boundsOnScreen] minus the window's `locationOnScreen`, read in one EDT pass. It uses
     * the same HiDPI scaling as [boundsOnScreen], so a 24.dp-wide node is 24 wide here on any
     * display scale. The offset includes window decorations such as the title bar, so the result
     * matches coordinates in the window's Swing hierarchy (for example from
     * `SwingUtilities.convertPoint` with the window as the destination). Unlike [boundsInWindow],
     * it is not in physical Compose pixels and is not relative to the Compose content.
     */
    public val boundsInAwtWindow: Rectangle
        get() = readOnEdt {
            val bounds = semanticsNode.boundsInWindow
            val transform = trackedWindow.window.graphicsConfiguration.defaultTransform
            composeBoundsToAwtWindowRectangle(
                left = bounds.left,
                top = bounds.top,
                right = bounds.right,
                bottom = bounds.bottom,
                scaleX = transform.scaleX.toFloat(),
                scaleY = transform.scaleY.toFloat(),
                contentOriginOnScreen = trackedWindow.composeContentOrigin,
                windowOriginOnScreen = trackedWindow.windowOriginOnScreen,
            )
        }

    /**
     * Atomic snapshot of both [boundsInWindow] and [boundsOnScreen] taken in a single EDT read.
     * Useful when callers need both forms — e.g. wire serialisation — and want them to reflect the
     * same underlying layout state. Reading the two getters separately would issue two independent
     * EDT bounces, between which layout could shift.
     */
    public fun bothBounds(): BothBounds = readOnEdt {
        val bounds = semanticsNode.boundsInWindow
        val transform = trackedWindow.window.graphicsConfiguration.defaultTransform
        val contentOrigin = trackedWindow.composeContentOrigin
        BothBounds(
            inWindow = bounds,
            onScreen =
                composeBoundsToAwtRectangle(
                    left = bounds.left,
                    top = bounds.top,
                    right = bounds.right,
                    bottom = bounds.bottom,
                    scaleX = transform.scaleX.toFloat(),
                    scaleY = transform.scaleY.toFloat(),
                    panelScreenX = contentOrigin.x,
                    panelScreenY = contentOrigin.y,
                ),
        )
    }

    public val parent: AutomatorNode?
        get() = relations.parentOf(key)

    public val children: List<AutomatorNode>
        get() = relations.childrenOf(key)

    override fun toString(): String = buildString {
        append("AutomatorNode(key=$key")
        testTag?.let { append(", tag=$it") }
        text?.let { append(", text=$it") }
        role?.let { append(", role=$it") }
        append(")")
    }
}

internal fun SemanticsConfiguration.toggleableStateOrNull(): ToggleableState? =
    getOrNull(SemanticsProperties.ToggleableState)

internal fun SemanticsConfiguration.progressBarRangeInfoOrNull(): ProgressBarRangeInfo? =
    getOrNull(SemanticsProperties.ProgressBarRangeInfo)

/** Atomic snapshot of [AutomatorNode.boundsInWindow] and [AutomatorNode.boundsOnScreen]. */
public data class BothBounds(val inWindow: Rect, val onScreen: Rectangle)

internal interface NodeRelations {

    fun parentOf(key: NodeKey): AutomatorNode?

    fun childrenOf(key: NodeKey): List<AutomatorNode>
}

private object EmptyNodeRelations : NodeRelations {

    override fun parentOf(key: NodeKey): AutomatorNode? = null

    override fun childrenOf(key: NodeKey): List<AutomatorNode> = emptyList()
}
