package dev.sebastiano.spectre.core

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import java.awt.Point

data class NodeKey(val surfaceId: String, val ownerIndex: Int, val nodeId: Int) {

    override fun toString(): String = "$surfaceId:$ownerIndex:$nodeId"

    companion object {

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

class AutomatorNode(
    val key: NodeKey,
    val semanticsNode: SemanticsNode,
    val trackedWindow: TrackedWindow,
) {

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
    val boundsInWindow: Rect = semanticsNode.boundsInWindow

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

    override fun toString(): String = buildString {
        append("AutomatorNode(key=$key")
        testTag?.let { append(", tag=$it") }
        text?.let { append(", text=$it") }
        role?.let { append(", role=$it") }
        append(")")
    }
}
