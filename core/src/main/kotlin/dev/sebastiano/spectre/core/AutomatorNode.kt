@file:OptIn(InternalSpectreApi::class)

package dev.sebastiano.spectre.core

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
data class NodeKey(val surfaceId: String, val ownerIndex: Int, val nodeId: Int) {

    override fun toString(): String = "$surfaceId:$ownerIndex:$nodeId"

    companion object {

        /**
         * Parses a string of the form `surfaceId:ownerIndex:nodeId` into a [NodeKey]. Throws
         * [IllegalArgumentException] on malformed input.
         */
        fun parse(key: String): NodeKey {
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
class AutomatorNode
internal constructor(
    val key: NodeKey,
    internal val semanticsNode: SemanticsNode,
    internal val trackedWindow: TrackedWindow,
    private val relations: NodeRelations = EmptyNodeRelations,
) {

    /** Stable identifier of the tracked window/surface this node belongs to. */
    val surfaceId: String = trackedWindow.surfaceId

    // Eagerly snapshot all semantics properties at construction time (which runs on
    // the EDT inside readAllNodes) so callers can safely read them from any thread.
    val testTag: String? = semanticsNode.config.getOrNull(SemanticsProperties.TestTag)
    val texts: List<String> =
        semanticsNode.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
    val text: String? = texts.firstOrNull()
    val editableText: String? =
        semanticsNode.config.getOrNull(SemanticsProperties.EditableText)?.text
    val contentDescriptions: List<String> =
        semanticsNode.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    val contentDescription: String? = contentDescriptions.firstOrNull()
    val role: Role? = semanticsNode.config.getOrNull(SemanticsProperties.Role)
    val isDisabled: Boolean = semanticsNode.config.getOrNull(SemanticsProperties.Disabled) != null
    val isFocused: Boolean = semanticsNode.config.getOrNull(SemanticsProperties.Focused) == true
    val isSelected: Boolean = semanticsNode.config.getOrNull(SemanticsProperties.Selected) == true

    // Geometry is always read live: layout can change between node lookup and action,
    // so a snapshot would let click/screenshot drift to stale coordinates after scrolling
    // or recomposition-driven repositioning.
    val boundsInWindow: Rect
        get() = readOnEdt { semanticsNode.boundsInWindow }

    val centerOnScreen: Point
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

    val boundsOnScreen: Rectangle
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
     * Atomic snapshot of both [boundsInWindow] and [boundsOnScreen] taken in a single EDT read.
     * Useful when callers need both forms — e.g. wire serialisation — and want them to reflect the
     * same underlying layout state. Reading the two getters separately would issue two independent
     * EDT bounces, between which layout could shift.
     */
    fun bothBounds(): BothBounds = readOnEdt {
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

    val parent: AutomatorNode?
        get() = relations.parentOf(key)

    val children: List<AutomatorNode>
        get() = relations.childrenOf(key)

    override fun toString(): String = buildString {
        append("AutomatorNode(key=$key")
        testTag?.let { append(", tag=$it") }
        text?.let { append(", text=$it") }
        role?.let { append(", role=$it") }
        append(")")
    }
}

/** Atomic snapshot of [AutomatorNode.boundsInWindow] and [AutomatorNode.boundsOnScreen]. */
data class BothBounds(val inWindow: Rect, val onScreen: Rectangle)

internal interface NodeRelations {

    fun parentOf(key: NodeKey): AutomatorNode?

    fun childrenOf(key: NodeKey): List<AutomatorNode>
}

private object EmptyNodeRelations : NodeRelations {

    override fun parentOf(key: NodeKey): AutomatorNode? = null

    override fun childrenOf(key: NodeKey): List<AutomatorNode> = emptyList()
}
