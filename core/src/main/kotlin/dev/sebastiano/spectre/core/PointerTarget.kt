package dev.sebastiano.spectre.core

import androidx.compose.ui.geometry.Rect
import java.awt.Point

/**
 * [node]'s on-screen centre for pointer input, or an [IllegalStateException] when the node has no
 * on-screen area to aim at.
 *
 * A node clipped entirely out of its scroll container's viewport stays in the semantics tree with
 * empty `boundsInWindow`. [composeBoundsToAwtRectangle] clamps those to 1x1 at the surface origin,
 * so [AutomatorNode.centerOnScreen] points at the surface's top-left corner and the input would
 * land on whatever sits there (#564). The bounds and the centre come from one EDT read, so they
 * describe the same layout pass.
 */
internal fun pointerTargetOnScreen(node: AutomatorNode, action: String): Point {
    val (bounds, center) = readOnEdt { node.boundsInWindow to node.centerOnScreen }
    checkPointerTargetBounds(bounds, action, node.toString())
    return center
}

/** Fails with a message naming [action] and [nodeDescription] when [boundsInWindow] is empty. */
internal fun checkPointerTargetBounds(
    boundsInWindow: Rect,
    action: String,
    nodeDescription: String,
) {
    check(!boundsInWindow.isEmpty) {
        "Cannot $action $nodeDescription: its in-window bounds are empty ($boundsInWindow), so it " +
            "has no on-screen point to target. The node is most likely scrolled outside its " +
            "container's viewport, or not measured yet. Scroll it into view first (for example " +
            "with scrollWheel on the scrollable container), then look the node up again. " +
            "node.boundsInWindow.isEmpty tells you in advance whether a node can be targeted."
    }
}
